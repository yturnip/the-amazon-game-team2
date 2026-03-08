package ubc.cosc322;

import sfs2x.client.entities.Room;
import ygraph.ai.smartfox.games.BaseGameGUI;
import ygraph.ai.smartfox.games.GameClient;
import ygraph.ai.smartfox.games.GameMessage;
import ygraph.ai.smartfox.games.GamePlayer;
import ygraph.ai.smartfox.games.amazons.AmazonsGameMessage;

import java.util.*;

/*
COSC322 Amazons Bot — Monte Carlo Tree Search V2 (Hybrid Phase Strategy)

Board convention (matching server):
- Rows/cols are 1-based (1..10)
- Flat index: (row-1)*10 + (col-1)
- Server game-state array is size 121, indexed as row*11+col

Piece values:  0=empty  1=black  2=white  3=arrow
BLACK (1) moves first.

The enhancement from V1 includes:
1. Progressive Bias: UCT score includes a diminishing heuristic bonus
2. Better Rollout: rollout uses mobility + territory combo heuristic
3. Move Ordering: untried list sorted by heuristic before expansion

What does it mean by hybrid phase strategy:
> Early game (arrows < LATE_THRESHOLD): use enhancements 1-3 for stronger but costlier iterations
	(quality matters more than quantity here.)
> Late game (arrows >= LATE_THRESHOLD): drop enhancements and fall back to cheap V1-style iterations
	(board is fragmented so more simulations win.)
*/

public class MCTS_V2 extends GamePlayer {

	private GameClient gameClient;
	private BaseGameGUI gamegui;
	private String userName;
	private String passwd;

	public static final int BLACK = GameBoard.BLACK;
	public static final int WHITE = GameBoard.WHITE;

	private GameBoard board = new GameBoard();
	private int myColor = 0;

	// MCTS budget
	private static final long TIME_LIMIT_MS = 29_000;
	private long mctsStart = 0;

	// Phase threshold: switch to cheap V1 mode once this many arrows are on board
	// Chosen 30 for now because the board s significantly fragmented into isolated regions at 30 arrows
	private static final int LATE_THRESHOLD = 30;

	public static void main(String[] args) {
		MCTS_V2 player = new MCTS_V2("mctsv2", "pwd");
		BaseGameGUI.sys_setup();
		java.awt.EventQueue.invokeLater(player::Go);
	}

	public MCTS_V2(String user, String pass) {
		this.userName = user;
		this.passwd   = pass;
		this.gamegui  = new BaseGameGUI(this);
	}

	@Override public void connect() {
		gameClient = new GameClient(userName, passwd, this);
	}

	@Override public void onLogin() {
		System.out.println("[BOT] Logged in as " + userName);
		gamegui.setRoomInformation(gameClient.getRoomList());
	}

	@Override
	public boolean handleGameMessage(String messageType, Map<String, Object> msgDetails) {
		System.out.println("[MSG] " + messageType);

		if (messageType.equals(GameMessage.GAME_STATE_BOARD)
				|| messageType.equals("cosc322.game-state.board")) {

			ArrayList<Integer> state = (ArrayList<Integer>) msgDetails.get("game-state");
			if (state != null) {
				board.initFromGameState(state);
				if (gamegui != null) gamegui.setGameState(state);
			}
			board.printBoard();
			return true;
		}

		if (messageType.equals(GameMessage.GAME_ACTION_START)) {
			String bp = (String) msgDetails.get(AmazonsGameMessage.PLAYER_BLACK);
			String wp = (String) msgDetails.get(AmazonsGameMessage.PLAYER_WHITE);
			if (bp != null) myColor = userName.equals(bp) ? BLACK : WHITE;
			System.out.println("[START] myColor=" + myColor);
			ArrayList<Integer> state = (ArrayList<Integer>) msgDetails.get("game-state");
			if (state != null) { board.initFromGameState(state); if (gamegui != null) gamegui.setGameState(state); }
			board.printBoard();
			if (myColor == BLACK) makeMCTSMove();
			return true;
		}

		if (messageType.equals(GameMessage.GAME_ACTION_MOVE)
				|| messageType.equals("cosc322.game-action.move")) {
			if (myColor == 0) { System.out.println("[WARN] Color unknown, skipping"); return true; }
			applyOpponentMove(msgDetails);
			if (gamegui != null) gamegui.updateGameState(msgDetails);
			makeMCTSMove();
			return true;
		}

		return true;
	}

	private void assignColorFromRoom() {
		if (myColor != 0) return;
		int numUsers = 99;
		try {
			for (Room room : gameClient.getRoomList()) {
				if (!room.getUserList().isEmpty()) {
					numUsers = room.getUserList().size();
					break;
				}
			}
		} catch (Exception e) {
			numUsers = 1;
		}
		myColor = (numUsers <= 1) ? BLACK : WHITE;
		System.out.println("[COLOR] numUsers=" + numUsers + " → myColor=" + myColor);
	}

	@SuppressWarnings("unchecked")
	private void applyOpponentMove(Map<String, Object> msg) {
		ArrayList<Integer> curr  = (ArrayList<Integer>) msg.get(AmazonsGameMessage.QUEEN_POS_CURR);
		ArrayList<Integer> next  = (ArrayList<Integer>) msg.get(AmazonsGameMessage.QUEEN_POS_NEXT);
		ArrayList<Integer> arrow = (ArrayList<Integer>) msg.get(AmazonsGameMessage.ARROW_POS);
		board.applyMove(
				new int[]{curr.get(0),  curr.get(1)},
				new int[]{next.get(0),  next.get(1)},
				new int[]{arrow.get(0), arrow.get(1)}
		);
		System.out.printf("[OPP] (%d,%d)->(%d,%d) arrow->(%d,%d)%n",
				curr.get(0), curr.get(1), next.get(0), next.get(1), arrow.get(0), arrow.get(1));
	}

	// Function to count the number of arrows on board
	private int countArrows(GameBoard b) {
		int count = 0;
		for (int v : b.getFlat()) if (v == GameBoard.ARROW) count++;
		return count;
	}

	//  MCTS
	private void makeMCTSMove() {
		int arrows = countArrows(board);
		boolean early = arrows < LATE_THRESHOLD;
		System.out.println("[MCTS] Deciding moves. Color=" + myColor
							+ " || Arrows=" + arrows
							+ " || Current Phase=" + (early ? "EARLY (V2 enhanced)" : "LATE (V1 model)"));
		mctsStart = System.currentTimeMillis();
		int[] best = mcts(board.copy(), myColor, early);
		if (best == null) { System.out.println("[MCTS] No moves left. Game over"); return; }

		board.applyPackedMove(best);
		sendMove(best);

		System.out.printf("[MCTS] queen (%d,%d)->(%d,%d) arrow->(%d,%d) in %dms%n",
				best[0],best[1],best[2],best[3],best[4],best[5],
				System.currentTimeMillis() - mctsStart);

		if (gamegui != null) gamegui.updateGameState(buildMoveMap(best));
	}

	// MCTS Node
	private static class Node {
		int[] move;
		int color;
		Node parent;
		List<Node> children = new ArrayList<>();
		List<int[]> untried;
		double wins = 0;
		int visits = 0;
		GameBoard  snap;   // board snapshot at this node
		double heuristic = 0; /* heuristic score of this node's move only,
		used to bias the UCT election towards promising unexplored nodes in early search */

		Node(GameBoard snap, int color, int[] move, Node parent) {
			this.snap = snap.copy(); this.color = color;
			this.move = move; this.parent = parent;
		}
		/* UCT with optional Progressive Bias term. In early game,
		the heuristic/(visits+1) bonus guides search toward promising nodes before
		we can rely on the trustworthiness of a node (does it lead to win?). In late game (bias=0),
		this reduces to standard UCT for maximum iteration throughput. */
		double uct(double c, boolean bias) {
			if (visits == 0) return Double.MAX_VALUE;
			double score = wins / visits + c * Math.sqrt(Math.log(parent.visits) / visits);
			if (bias) score += heuristic / (visits + 1);
			return score;
		}
	}

	private int[] mcts(GameBoard rootBoard, int rootColor, boolean earlyGame) {
		List<int[]> rootMoves = rootBoard.generateMoves(rootColor);
		if (rootMoves.isEmpty()) return null;
		if (rootMoves.size() == 1) return rootMoves.get(0);

		Node root = new Node(rootBoard, rootColor, null, null);
		/* sort untried moves by heuristic so the best moves are expanded first
		improving early cutoff */
		root.untried = earlyGame ? orderMoves(rootBoard, rootMoves, rootColor) : shuffled(rootMoves);

		int iters = 0;
		while (System.currentTimeMillis() - mctsStart < TIME_LIMIT_MS) {
			Node node = select(root, earlyGame);
			if (node.untried != null && !node.untried.isEmpty()) node = expand(node, earlyGame);
			double result = simulate(node.snap.copy(), node.color, earlyGame);
			backprop(node, result);
			iters++;
		}
		System.out.println("[MCTS] Iterations=" + iters);

		return root.children.stream()
				.max(Comparator.comparingInt(n -> n.visits))
				.map(n -> n.move)
				.orElse(rootMoves.get(0));
	}

	private Node select(Node node, boolean earlyGame) {
		while (node.untried != null && node.untried.isEmpty() && !node.children.isEmpty())
			node = node.children.stream()
					.max(Comparator.comparingDouble(n -> n.uct(1.41, earlyGame)))
					.orElse(node.children.get(0));
		return node;
	}

	private Node expand(Node node, boolean earlyGame) {
		// Takes from the end of the list (highest heuristic score first in early game)
		int[] move = node.untried.remove(node.untried.size() - 1);
		GameBoard nb = node.snap.withPackedMove(move, node.color);
		int next = opp(node.color);
		Node child = new Node(nb, next, move, node);

		List<int[]> childMoves = nb.generateMoves(next);
		// Move ordering (early game only)
		child.untried = earlyGame ? orderMoves(nb, childMoves, next) : shuffled(childMoves);

		// Progressive bias (early game only): store heuristic on child node
		if (earlyGame) child.heuristic = moveHeuristic(node.snap, move, node.color);

		node.children.add(child);
		return child;
	}

	private double simulate(GameBoard snap, int color, boolean earlyGame) {
		GameBoard b = snap;
		int turn = color;
		Random rng  = new Random();
		for (int d = 0; d < 30; d++) {
			if (System.currentTimeMillis() - mctsStart >= TIME_LIMIT_MS)
				return b.eval(myColor);
			List<int[]> moves = b.generateMoves(turn);
			if (moves.isEmpty()) return turn == myColor ? 0.0 : 1.0;
			/* Pick move on current board state, then advance to next state
			Better rollout (early game): combo heuristic
			Fast rollout (late game): cheap territory-only, smaller sample */
			int[] chosen = earlyGame ? pickMoveEarly(b, moves, turn, rng) : pickMoveLate(b, moves, turn, rng);
			b = b.withPackedMove(chosen, turn);
			turn = opp(turn);
		}
		return b.eval(myColor);
	}

	// Early game: territory + mobility combo heuristic, sample 20
	private int[] pickMoveEarly(GameBoard b, List<int[]> moves, int color, Random rng) {
		if (rng.nextDouble() < 0.8) {
			int[] best   = null;
			double bs     = Double.NEGATIVE_INFINITY;
			int sample = Math.min(moves.size(), 20);
			for (int i = 0; i < sample; i++) {
				int[] m = moves.get(rng.nextInt(moves.size()));
				double s = moveHeuristic(b, m, color);
				if (s > bs) { bs = s; best = m; }
			}
			return best;
		}
		return moves.get(rng.nextInt(moves.size()));
	}

	// Late game: territory-only heuristic, larger sample for speed
	private int[] pickMoveLate(GameBoard b, List<int[]> moves, int color, Random rng) {
		if (rng.nextDouble() < 0.8) {
			int[] best   = null;
			double bs     = Double.NEGATIVE_INFINITY;
			int sample = Math.min(moves.size(), 10);
			for (int i = 0; i < sample; i++) {
				int[] m = moves.get(rng.nextInt(moves.size()));
				double s = b.withPackedMove(m, color).territoryDiff(color);
				if (s > bs) { bs = s; best = m; }
			}
			return best;
		}
		return moves.get(rng.nextInt(moves.size()));
	}

	/*
	Combined heuristic for a move: territory difference + mobility ratio.
	 - Territory: BFS-based W1 score (cells closer to us than opponent).
	 - Mobility:  (our slides after) - (opponent slides after), normalised.
	 - Weighting territory 70% and mobility 30% balances long-term control
	   with immediate tactical threats.
	 */
	private double moveHeuristic(GameBoard b, int[] m, int color) {
		GameBoard nb = b.withPackedMove(m, color);
		double territory = nb.territoryDiff(color);
		double mobility  = mobilityScore(nb, color) - mobilityScore(nb, opp(color));
		return 0.7 * territory + 0.3 * mobility;
	}

	// Counts total queen-slide squares available to all queens of color.
	private double mobilityScore(GameBoard b, int color) {
		double total = 0;
		int[] flat = b.getFlat();
		for (int i = 0; i < GameBoard.SIZE * GameBoard.SIZE; i++) {
			if (flat[i] != color) continue;
			total += b.slides(GameBoard.row(i), GameBoard.col(i)).size();
		}
		return total;
	}

	private List<int[]> orderMoves(GameBoard b, List<int[]> moves, int color) {
		List<int[]> ordered = new ArrayList<>(moves);
		ordered.sort(Comparator.comparingDouble(m -> moveHeuristic(b, m, color)));
		return ordered;
	}

	private List<int[]> shuffled(List<int[]> moves) {
		List<int[]> copy = new ArrayList<>(moves);
		Collections.shuffle(copy);
		return copy;
	}

	private void backprop(Node node, double result) {
		while (node!=null) { node.visits++; node.wins+=result; node=node.parent; }
	}

	private int opp(int c) { return c==BLACK ? WHITE : BLACK; }

	//  Networking
	private void sendMove(int[] m) { gameClient.sendMoveMessage(buildMoveMap(m)); }

	private Map<String, Object> buildMoveMap(int[] m) {
		Map<String, Object> msg = new HashMap<>();
		msg.put(AmazonsGameMessage.QUEEN_POS_CURR, toList(m[0], m[1]));
		msg.put(AmazonsGameMessage.QUEEN_POS_NEXT, toList(m[2], m[3]));
		msg.put(AmazonsGameMessage.ARROW_POS,      toList(m[4], m[5]));
		return msg;
	}

	private ArrayList<Integer> toList(int r,int c) { return new ArrayList<>(Arrays.asList(r,c)); }

	@Override public String      userName()      { return userName; }
	@Override public GameClient  getGameClient() { return gameClient; }
	@Override public BaseGameGUI getGameGUI()    { return gamegui; }
}