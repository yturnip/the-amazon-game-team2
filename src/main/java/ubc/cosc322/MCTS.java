package ubc.cosc322;

import java.util.*;
import ygraph.ai.smartfox.games.*;
import ygraph.ai.smartfox.games.amazons.AmazonsGameMessage;
import sfs2x.client.entities.Room;

/**
 * COSC322 Amazons Bot — Monte Carlo Tree Search
 *
 * Board convention (matching server):
 *   Rows/cols are 1-based (1..10)
 *   Flat index: (row-1)*10 + (col-1)
 *   Server game-state array is size 121, indexed as row*11+col
 *
 * Piece values:  0=empty  1=black  2=white  3=arrow
 * BLACK (1) moves first.
 */

public class MCTS extends GamePlayer {

	private GameClient  gameClient;
	private BaseGameGUI gamegui;
	private String      userName;
	private String      passwd;

	public static final int BLACK = GameBoard.BLACK;
	public static final int WHITE = GameBoard.WHITE;

	private GameBoard board = new GameBoard();
	private int myColor = 0;

	// MCTS budget
	private static final long TIME_LIMIT_MS = 29_000;
	private long mctsStart = 0;

	public static void main(String[] args) {
		MCTS player = new MCTS("player1", "pwd");
		BaseGameGUI.sys_setup();
		java.awt.EventQueue.invokeLater(player::Go);
	}

	public MCTS(String user, String pass) {
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
			assignColorFromRoom();
			ArrayList<Integer> state = (ArrayList<Integer>) msgDetails.get("game-state");
			if (state != null) {
				board.initFromGameState(state);
				if (gamegui != null) gamegui.setGameState(state);
			}
			board.printBoard();
			if (myColor == BLACK) makeMCTSMove();
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
			if (myColor == 0) { System.out.println("[WARN] color unknown, skipping"); return true; }
			applyOpponentMove(msgDetails);
			if (gamegui != null) gamegui.updateGameState(msgDetails);
			makeMCTSMove();
			return true;
		}

		return true;
	}

	//  Color assignment
	private void assignColorFromRoom() {
		if (myColor != 0) return;
		int numUsers = 99;
		try {
			for (Room room : gameClient.getRoomList()) {
				if (!room.getUserList().isEmpty()) { numUsers = room.getUserList().size(); break; }
			}
		} catch (Exception e) { numUsers = 1; }
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

	//  MCTS
	private void makeMCTSMove() {
		System.out.println("[MCTS] Thinking... color=" + myColor);
		mctsStart = System.currentTimeMillis();
		int[] best = mcts(board.copy(), myColor);
		if (best == null) { System.out.println("[MCTS] No moves — game over"); return; }

		board.applyPackedMove(best);
		sendMove(best);

		System.out.printf("[MCTS] queen (%d,%d)->(%d,%d) arrow->(%d,%d) in %dms%n",
				best[0],best[1],best[2],best[3],best[4],best[5],
				System.currentTimeMillis() - mctsStart);

		if (gamegui != null) gamegui.updateGameState(buildMoveMap(best));
	}

	// MCTS Node
	private static class Node {
		int[]      move;
		int        color;
		Node       parent;
		List<Node> children = new ArrayList<>();
		List<int[]> untried;
		double     wins    = 0;
		int        visits  = 0;
		GameBoard  snap;   // board snapshot at this node

		Node(GameBoard snap, int color, int[] move, Node parent) {
			this.snap = snap; this.color = color;
			this.move = move; this.parent = parent;
		}
		double uct(double c) {
			if (visits == 0) return Double.MAX_VALUE;
			return wins / visits + c * Math.sqrt(Math.log(parent.visits) / visits);
		}
	}

	private int[] mcts(GameBoard rootBoard, int rootColor) {
		List<int[]> rootMoves = rootBoard.generateMoves(rootColor);
		if (rootMoves.isEmpty()) return null;
		if (rootMoves.size() == 1) return rootMoves.get(0);

		Node root    = new Node(rootBoard, rootColor, null, null);
		root.untried = new ArrayList<>(rootMoves);
		Collections.shuffle(root.untried);

		int iters = 0;
		while (System.currentTimeMillis() - mctsStart < TIME_LIMIT_MS) {
			Node   node   = select(root);
			if (node.untried != null && !node.untried.isEmpty()) node = expand(node);
			double result = simulate(node.snap.copy(), node.color);
			backprop(node, result);
			iters++;
		}
		System.out.println("[MCTS] iterations=" + iters);

		return root.children.stream()
				.max(Comparator.comparingInt(n -> n.visits))
				.map(n -> n.move)
				.orElse(rootMoves.get(0));
	}

	private Node select(Node node) {
		while (node.untried != null && node.untried.isEmpty() && !node.children.isEmpty())
			node = node.children.stream()
					.max(Comparator.comparingDouble(n -> n.uct(1.41)))
					.orElse(node.children.get(0));
		return node;
	}

	private Node expand(Node node) {
		int[]     move  = node.untried.remove(node.untried.size() - 1);
		GameBoard nb    = node.snap.withPackedMove(move, node.color);
		int       next  = opp(node.color);
		Node      child = new Node(nb, next, move, node);
		child.untried = new ArrayList<>(nb.generateMoves(next));
		Collections.shuffle(child.untried);
		node.children.add(child);
		return child;
	}

	private double simulate(GameBoard snap, int color) {
		int    turn = color;
		Random rng  = new Random();
		for (int d = 0; d < 30; d++) {
			if (System.currentTimeMillis() - mctsStart >= TIME_LIMIT_MS)
				return snap.eval(myColor);
			List<int[]> moves = snap.generateMoves(turn);
			if (moves.isEmpty()) return turn == myColor ? 0.0 : 1.0;
			snap.applyPackedMove(pickMove(snap, moves, turn, rng));
			turn = opp(turn);
		}
		return snap.eval(myColor);
	}

	private int[] pickMove(GameBoard b, List<int[]> moves, int color, Random rng) {
		if (rng.nextDouble() < 0.8) {
			int[]  best   = null;
			double bs     = Double.NEGATIVE_INFINITY;
			int    sample = Math.min(moves.size(), 20);
			for (int i = 0; i < sample; i++) {
				int[] m = moves.get(rng.nextInt(moves.size()));
				double s = b.copy().withPackedMove(m, color).territoryDiff(color);
				if (s > bs) { bs = s; best = m; }
			}
			return best;
		}
		return moves.get(rng.nextInt(moves.size()));
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