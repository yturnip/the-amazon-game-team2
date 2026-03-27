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

Enhancements over V1:
1. UCT + Progressive Bias — heuristic bonus on UCT that shrinks as node matures
2. Better rollout  — territory + mobility + region combo heuristic
3. Move ordering   — untried list sorted by heuristic before expansion
4. Transposition table — Zobrist-hashed cache reuses simulation results
5. Move pruning    — removes self-trapping moves before rollout sampling

Hybrid phase strategy:
> Early game (arrows < LATE_THRESHOLD): enhancements 1-3 ON
> Late game  (arrows >= LATE_THRESHOLD): cheap V1-style iterations for more throughput
*/

public class MCTS_V3 extends GamePlayer {

	private GameClient  gameClient;
	private BaseGameGUI gamegui;
	private String      userName;
	private String      passwd;

	public static final int BLACK = GameBoard.BLACK;
	public static final int WHITE = GameBoard.WHITE;

	private GameBoard board   = new GameBoard();
	private int       myColor = 0;

	// Single shared Random — creating new Random() per simulate() call wastes
	// CPU on seeding and object allocation across thousands of iterations
	private final Random rng = new Random();

	// MCTS budget
	private static final long TIME_LIMIT_MS = 29_000;
	private long mctsStart = 0;

	// Phase threshold: switch to cheap V1 mode once this many arrows are on board.
	// At 30 arrows the board is significantly fragmented into isolated regions.
	private static final int LATE_THRESHOLD = 30;

	// UCT exploration constant and progressive bias weight.
	// C=1.2 balances exploration/exploitation for Amazons' large branching factor.
	// BIAS_W scales the heuristic bonus — shrinks naturally as visits grow so
	// early guidance fades once the node has real statistics to rely on.
	private static final double UCT_C  = 1.2;
	private static final double BIAS_W = 1.0;

	// Transposition table: maps Zobrist board hash → cached node statistics.
	// When MCTS reaches a position it has seen before (via a different move order),
	// it reuses the stored wins/visits instead of starting from zero — effectively
	// merging search across transpositions and deepening the tree for free.
	// Cleared at the start of each move so stale data never crosses turns.
	private static final int TT_SIZE = 1 << 20; // 1M entries (~24MB)
	private final TTEntry[] tt = new TTEntry[TT_SIZE];

	private static class TTEntry {
		long   hash;
		double wins;
		int    visits;
		TTEntry(long h, double w, int v) { hash = h; wins = w; visits = v; }
	}

	public static void main(String[] args) {
		MCTS_V3 player = new MCTS_V3("mctsv3", "pwd");
		BaseGameGUI.sys_setup();
		java.awt.EventQueue.invokeLater(player::Go);
	}

	public MCTS_V3(String user, String pass) {
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
			// Only load board state here — do NOT move yet.
			// Color is assigned and moves are triggered by GAME_ACTION_START.
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

	// Returns number of arrows currently on the board — used for phase detection.
	private int countArrows(GameBoard b) {
		int count = 0;
		for (int v : b.getFlat()) if (v == GameBoard.ARROW) count++;
		return count;
	}

	//  MCTS
	private void makeMCTSMove() {
		int arrows    = countArrows(board);
		boolean early = arrows < LATE_THRESHOLD;
		System.out.println("[MCTS] Deciding moves. Color=" + myColor
				+ " || Arrows=" + arrows
				+ " || Phase=" + (early ? "EARLY (UCT+bias+region)" : "LATE (UCT fast mode)"));

		mctsStart = System.currentTimeMillis();
		Arrays.fill(tt, null); // clear stale entries from previous move
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
		int[]       move;
		int         color;
		Node        parent;
		List<Node>  children = new ArrayList<>();
		List<int[]> untried;
		double      wins      = 0;
		int         visits    = 0;
		GameBoard   snap;     // board snapshot at this node

		// Progressive bias: raw heuristic score set once at expansion time.
		// Dividing by (visits+1) makes the bonus shrink as the node accumulates
		// real statistics — early guidance fades, hard data takes over.
		double heuristic = 0;

		Node(GameBoard snap, int color, int[] move, Node parent) {
			this.snap   = snap.copy();
			this.color  = color;
			this.move   = move;
			this.parent = parent;
		}

		// UCT with progressive bias term.
		// Early on heuristic/(visits+1) steers search toward promising nodes.
		// As visits grow the bias shrinks and standard UCT statistics dominate.
		double uct(double c, double biasW) {
			if (visits == 0) return Double.MAX_VALUE;
			return wins / visits
					+ c    * Math.sqrt(Math.log(parent.visits) / visits)
					+ biasW * heuristic / (visits + 1);
		}
	}

	private int[] mcts(GameBoard rootBoard, int rootColor, boolean earlyGame) {
		List<int[]> rootMoves = rootBoard.generateMoves(rootColor);
		if (rootMoves.isEmpty()) return null;
		if (rootMoves.size() == 1) return rootMoves.get(0);

		Node root    = new Node(rootBoard, rootColor, null, null);
		// Move ordering (early game): sort by heuristic so best moves expand first
		root.untried = earlyGame
				? orderMoves(rootBoard, rootMoves, rootColor)
				: shuffled(rootMoves);

		int iters = 0;
		while (System.currentTimeMillis() - mctsStart < TIME_LIMIT_MS) {
			Node   node   = select(root, earlyGame);
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
		// Early game: UCT + progressive bias guides search toward heuristically
		// good nodes before statistics are reliable.
		// Late game: bias=0 so pure UCT maximises iteration throughput.
		double biasW = earlyGame ? BIAS_W : 0.0;
		while (node.untried != null && node.untried.isEmpty() && !node.children.isEmpty())
			node = node.children.stream()
					.max(Comparator.comparingDouble(n -> n.uct(UCT_C, biasW)))
					.orElse(node.children.get(0));
		return node;
	}

	private Node expand(Node node, boolean earlyGame) {
		// Takes from the end of the ordered list (highest heuristic score first)
		int[]     move  = node.untried.remove(node.untried.size() - 1);
		GameBoard nb    = node.snap.withPackedMove(move, node.color);
		int       next  = opp(node.color);
		Node      child = new Node(nb, next, move, node);

		List<int[]> childMoves = nb.generateMoves(next);
		child.untried = earlyGame ? orderMoves(nb, childMoves, next) : shuffled(childMoves);

		// Progressive bias (early game only): store raw heuristic score once.
		// Computed on the parent's board state before the move is applied so
		// we capture the move's value in context. Never recomputed — the bias
		// naturally fades via the 1/(visits+1) denominator in uct().
		if (earlyGame) child.heuristic = moveHeuristic(node.snap, move, node.color);

		node.children.add(child);
		return child;
	}

	private double simulate(GameBoard snap, int color, boolean earlyGame) {
		// Rollout depth scales with phase: deeper in early game where positions
		// are open, shallower in late game where regions are already decided
		int       maxDepth = earlyGame ? 25 : 12;
		GameBoard b        = snap;
		int       turn     = color;

		// Incremental Zobrist hash — only initialised in late game where TT is active.
		// Maintained by updateHash() O(1) per step instead of recomputing all 100
		// cells from scratch. Early game gets 0L and never touches the TT at all.
		long hash = earlyGame ? 0L : b.zobristHash();

		for (int d = 0; d < maxDepth; d++) {
			if (System.currentTimeMillis() - mctsStart >= TIME_LIMIT_MS)
				return b.evalW2(myColor);

			// Transposition table: late game only (arrows >= LATE_THRESHOLD).
			// Early game positions almost never repeat so TT hit rate is near zero
			// — paying the hashing cost with no benefit hurts iteration count.
			if (!earlyGame) {
				TTEntry cached = ttGet(hash);
				if (cached != null && cached.visits >= 3)
					return cached.wins / cached.visits;
			}

			List<int[]> moves = b.generateMoves(turn);
			if (moves.isEmpty()) return turn == myColor ? 0.0 : 1.0;

			// Move pruning: remove immediately self-trapping moves before sampling.
			// A move that leaves the queen with 0 slides after placing the arrow
			// is never worth playing — prune it to improve rollout quality.
			moves = pruneTrapped(b, moves, turn);
			if (moves.isEmpty()) return turn == myColor ? 0.0 : 1.0;

			// Better rollout (early): combo heuristic
			// Fast rollout (late): cheap territory-only, smaller sample
			int[] chosen = earlyGame
					? pickMoveEarly(b, moves, turn)
					: pickMoveLate(b, moves, turn);

			// Apply move — use applyTempMove so we can update hash incrementally.
			// In late game also store the new position in the TT for future lookups.
			int[] undo = b.applyTempMove(chosen);
			if (!earlyGame) {
				hash = GameBoard.updateHash(hash, chosen, undo);
				ttPut(hash, b.evalW2(myColor));
			}
			turn = opp(turn);
		}
		return b.evalW2(myColor);
	}

	// Removes moves that immediately trap the moving queen (0 slides after arrow).
	// These are objectively bad moves in Amazons — a trapped queen contributes
	// nothing for the rest of the game. Pruning them improves rollout signal.
	// Falls back to the full list if all moves would trap (unlikely but safe).
	private List<int[]> pruneTrapped(GameBoard b, List<int[]> moves, int color) {
		// Use applyTempMove/undo to check mobility without allocating board copies
		List<int[]> safe = new ArrayList<>();
		for (int[] m : moves) {
			int[] undo = b.applyTempMove(m);
			boolean trapped = b.slides(m[2], m[3]).isEmpty();
			b.undoTempMove(m, undo);
			if (!trapped) safe.add(m);
		}
		return safe.isEmpty() ? moves : safe;
	}

	// Transposition table get: returns entry only if hash matches (collision check).
	private TTEntry ttGet(long hash) {
		TTEntry e = tt[(int)(hash & (TT_SIZE - 1))];
		return (e != null && e.hash == hash) ? e : null;
	}

	// Transposition table put: store wins/visits for this board hash.
	private void ttPut(long hash, double eval) {
		int idx = (int)(hash & (TT_SIZE - 1));
		TTEntry e = tt[idx];
		if (e == null || e.hash == hash) {
			// New entry or same position — update
			if (e == null) tt[idx] = new TTEntry(hash, eval, 1);
			else           { e.wins += eval; e.visits++; }
		}
		// Collision: keep existing entry (replace-always would also work)
	}

	// Early game: full heuristic (territory + mobility + region), sample 20.
	// Region BFS is worth the cost here since we only sample 20 moves, not
	// score the entire move list like orderMoves does.
	private int[] pickMoveEarly(GameBoard b, List<int[]> moves, int color) {
		if (rng.nextDouble() < 0.8) {
			int[]  best   = null;
			double bs     = Double.NEGATIVE_INFINITY;
			int    sample = Math.min(moves.size(), 20);
			for (int i = 0; i < sample; i++) {
				int[]  m = moves.get(rng.nextInt(moves.size()));
				double s = moveHeuristicFull(b, m, color);
				if (s > bs) { bs = s; best = m; }
			}
			return best;
		}
		return moves.get(rng.nextInt(moves.size()));
	}

	// Late game: territory-only heuristic, smaller sample for speed
	private int[] pickMoveLate(GameBoard b, List<int[]> moves, int color) {
		if (rng.nextDouble() < 0.8) {
			int[]  best   = null;
			double bs     = Double.NEGATIVE_INFINITY;
			int    sample = Math.min(moves.size(), 10);
			for (int i = 0; i < sample; i++) {
				int[]  m    = moves.get(rng.nextInt(moves.size()));
				int[]  undo = b.applyTempMove(m);
				double s    = b.territoryDiffW2(color);
				b.undoTempMove(m, undo);
				if (s > bs) { bs = s; best = m; }
			}
			return best;
		}
		return moves.get(rng.nextInt(moves.size()));
	}

	/*
	Full heuristic for rollout move selection: territory + mobility + region.
	 - Territory: BFS-based W1 score (cells closer to us than opponent).
	 - Mobility:  (our slides) - (opponent slides) after the move.
	 - Region:    size of largest open area reachable from the moved queen.
	              Rewards moves that claim large open space — directly counters
	              opponents who rush a queen to claim a quadrant early.
	Only used in pickMoveEarly — too expensive for ordering/expand.
	Weights: territory 55%, mobility 25%, region 20%.
	*/
	private double moveHeuristicFull(GameBoard b, int[] m, int color) {
		// Apply move in-place and undo afterward — avoids allocating a board copy.
		// W2 territory used here too for consistent tactical accuracy in rollouts.
		int[] undo = b.applyTempMove(m);
		double territory = b.territoryDiffW2(color);
		double mobility  = mobilityScore(b, color) - mobilityScore(b, opp(color));
		double region    = largestReachableRegion(b, m[2], m[3]);
		b.undoTempMove(m, undo);
		return 0.55 * territory + 0.25 * mobility + 0.20 * region;
	}

	/*
	Cheap heuristic for move ordering and expand: territory + mobility only.
	No region BFS — called thousands of times per move during orderMoves and
	expand so cost matters more than signal quality here.
	Weights: territory 70%, mobility 30%.
	*/
	private double moveHeuristic(GameBoard b, int[] m, int color) {
		// Apply move in-place and undo afterward — avoids allocating a board copy.
		// W2 territory measures immediate 1-move reachability — much more tactically
		// accurate than W1 for ordering moves in the early/midgame.
		int[] undo = b.applyTempMove(m);
		double territory = b.territoryDiffW2(color);
		double mobility  = mobilityScore(b, color) - mobilityScore(b, opp(color));
		b.undoTempMove(m, undo);
		return 0.70 * territory + 0.30 * mobility;
	}

	// Counts total queen-slide squares available to all queens of color.
	private double mobilityScore(GameBoard b, int color) {
		double total = 0;
		int[]  flat  = b.getFlat();
		for (int i = 0; i < GameBoard.SIZE * GameBoard.SIZE; i++) {
			if (flat[i] != color) continue;
			total += b.slides(GameBoard.row(i), GameBoard.col(i)).size();
		}
		return total;
	}

	// BFS from the queen's new position (m[2],m[3]) counting all empty cells
	// reachable by queen slides. A large region means the queen has claimed
	// open space that the opponent cannot easily contest.
	private int largestReachableRegion(GameBoard b, int r, int c) {
		boolean[] visited = new boolean[GameBoard.SIZE * GameBoard.SIZE];
		Queue<int[]> queue = new ArrayDeque<>();
		queue.add(new int[]{r, c});
		visited[GameBoard.flat(r, c)] = true;
		int   count = 0;
		int[] flat  = b.getFlat();
		int[] DR    = {-1,-1,-1, 0, 0, 1, 1, 1};
		int[] DC    = {-1, 0, 1,-1, 1,-1, 0, 1};
		while (!queue.isEmpty()) {
			int[] cur = queue.poll();
			for (int d = 0; d < 8; d++) {
				int nr = cur[0] + DR[d], nc = cur[1] + DC[d];
				while (GameBoard.inBounds(nr, nc)
						&& flat[GameBoard.flat(nr, nc)] == GameBoard.EMPTY) {
					int idx = GameBoard.flat(nr, nc);
					if (!visited[idx]) {
						visited[idx] = true;
						count++;
						queue.add(new int[]{nr, nc});
					}
					nr += DR[d]; nc += DC[d];
				}
			}
		}
		return count;
	}

	// Move ordering: sort ascending so highest-scoring moves are at the end
	// (removed last = expanded first). Used for both root and child expansion.
	private List<int[]> orderMoves(GameBoard b, List<int[]> moves, int color) {
		List<int[]> ordered = new ArrayList<>(moves);
		ordered.sort(Comparator.comparingDouble(m -> moveHeuristic(b, m, color)));
		return ordered;
	}

	// Returns a shuffled copy of the move list — used in late game mode.
	private List<int[]> shuffled(List<int[]> moves) {
		List<int[]> copy = new ArrayList<>(moves);
		Collections.shuffle(copy);
		return copy;
	}

	private void backprop(Node node, double result) {
		while (node != null) {
			node.visits++;
			node.wins += result;
			node = node.parent;
		}
	}

	private int opp(int c) { return c == BLACK ? WHITE : BLACK; }

	//  Networking
	private void sendMove(int[] m) { gameClient.sendMoveMessage(buildMoveMap(m)); }

	private Map<String, Object> buildMoveMap(int[] m) {
		Map<String, Object> msg = new HashMap<>();
		msg.put(AmazonsGameMessage.QUEEN_POS_CURR, toList(m[0], m[1]));
		msg.put(AmazonsGameMessage.QUEEN_POS_NEXT, toList(m[2], m[3]));
		msg.put(AmazonsGameMessage.ARROW_POS,      toList(m[4], m[5]));
		return msg;
	}

	private ArrayList<Integer> toList(int r, int c) { return new ArrayList<>(Arrays.asList(r, c)); }

	@Override public String      userName()      { return userName; }
	@Override public GameClient  getGameClient() { return gameClient; }
	@Override public BaseGameGUI getGameGUI()    { return gamegui; }
}