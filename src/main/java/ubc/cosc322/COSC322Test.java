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
public class COSC322Test extends GamePlayer {

	// ── connection / GUI ─────────────────────────────────────────────────────
	private GameClient  gameClient;
	private BaseGameGUI gamegui;
	private String      userName;
	private String      passwd;

	// ── game state ───────────────────────────────────────────────────────────
	private static final int SIZE  = 10;
	private static final int BLACK = 1;
	private static final int WHITE = 2;
	private static final int ARROW = 3;

	private int[] board   = new int[SIZE * SIZE];
	private int   myColor = 0;  // 1=black  2=white  0=unknown

	// ── MCTS budget ──────────────────────────────────────────────────────────
	private static final long TIME_LIMIT_MS = 29_000;
	private long mctsStart = 0;

	// ── directions ───────────────────────────────────────────────────────────
	private static final int[] DR = {-1,-1,-1, 0, 0, 1, 1, 1};
	private static final int[] DC = {-1, 0, 1,-1, 1,-1, 0, 1};

	// =========================================================================
	//  Entry point
	// =========================================================================
	public static void main(String[] args) {
		COSC322Test player = new COSC322Test("player1", "pwd");
		BaseGameGUI.sys_setup();
		java.awt.EventQueue.invokeLater(player::Go);
	}

	public COSC322Test(String user, String pass) {
		this.userName = user;
		this.passwd   = pass;
		this.gamegui  = new BaseGameGUI(this);
	}

	// =========================================================================
	//  GamePlayer interface
	// =========================================================================
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

		// ── board state ──────────────────────────────────────────────────────
		if (messageType.equals(GameMessage.GAME_STATE_BOARD)
				|| messageType.equals("cosc322.game-state.board")) {
			assignColorFromRoom();
			loadBoardState(msgDetails);
			printBoard();
			if (myColor == BLACK) makeMCTSMove(); // black moves first
			return true;
		}

		// ── explicit game-start (backup) ─────────────────────────────────────
		if (messageType.equals(GameMessage.GAME_ACTION_START)) {
			String bp = (String) msgDetails.get(AmazonsGameMessage.PLAYER_BLACK);
			String wp = (String) msgDetails.get(AmazonsGameMessage.PLAYER_WHITE);
			if (bp != null) myColor = userName.equals(bp) ? BLACK : WHITE;
			System.out.println("[START] myColor=" + myColor + (myColor==BLACK?" (BLACK)":" (WHITE)"));
			loadBoardState(msgDetails);
			printBoard();
			if (myColor == BLACK) makeMCTSMove();
			return true;
		}

		// ── opponent move ─────────────────────────────────────────────────────
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

	// =========================================================================
	//  Color assignment
	// =========================================================================
	private void assignColorFromRoom() {
		if (myColor != 0) return;

		int numUsers = 99;
		try {
			List<Room> rooms = gameClient.getRoomList();
			for (Room room : rooms) {
				List<?> users = room.getUserList();
				System.out.println("[ROOM] '" + room.getName() + "' users=" + users.size());
				if (users.size() > 0) { numUsers = users.size(); break; }
			}
		} catch (Exception e) {
			System.out.println("[ROOM] error: " + e.getMessage());
			numUsers = 1;
		}

		myColor = (numUsers <= 1) ? BLACK : WHITE;
		System.out.println("[COLOR] numUsers=" + numUsers
				+ " → myColor=" + myColor + (myColor==BLACK?" (BLACK)":" (WHITE)"));
	}

	// =========================================================================
	//  Board helpers
	// =========================================================================
	@SuppressWarnings("unchecked")
	private void loadBoardState(Map<String, Object> msg) {
		ArrayList<Integer> state = (ArrayList<Integer>) msg.get("game-state");
		if (state == null) { System.out.println("[WARN] no game-state"); return; }
		System.out.println("[BOARD] state size=" + state.size());
		int sz = state.size();
		if (sz == 121) {
			for (int r = 1; r <= SIZE; r++)
				for (int c = 1; c <= SIZE; c++)
					board[flat(r,c)] = state.get(r * 11 + c);
		} else if (sz == 100) {
			for (int i = 0; i < 100; i++) board[i] = state.get(i);
		} else {
			System.out.println("[WARN] unexpected state size " + sz);
			for (int i = 0; i < sz; i++)
				if (state.get(i) != 0) System.out.println("  [" + i + "]=" + state.get(i));
		}
		if (gamegui != null) gamegui.setGameState(state);
	}

	@SuppressWarnings("unchecked")
	private void applyOpponentMove(Map<String, Object> msg) {
		ArrayList<Integer> curr  = (ArrayList<Integer>) msg.get(AmazonsGameMessage.QUEEN_POS_CURR);
		ArrayList<Integer> next  = (ArrayList<Integer>) msg.get(AmazonsGameMessage.QUEEN_POS_NEXT);
		ArrayList<Integer> arrow = (ArrayList<Integer>) msg.get(AmazonsGameMessage.ARROW_POS);

		int cr=curr.get(0), cc=curr.get(1);
		int nr=next.get(0), nc=next.get(1);
		int ar=arrow.get(0), ac=arrow.get(1);

		int color = board[flat(cr,cc)];
		board[flat(cr,cc)] = 0;
		board[flat(nr,nc)] = color;
		board[flat(ar,ac)] = ARROW;
		System.out.printf("[OPP] (%d,%d)->(%d,%d) arrow->(%d,%d)%n", cr,cc,nr,nc,ar,ac);
	}

	private int     flat(int r,int c)     { return (r-1)*SIZE+(c-1); }
	private int     row(int i)            { return i/SIZE+1; }
	private int     col(int i)            { return i%SIZE+1; }
	private boolean inBounds(int r,int c) { return r>=1&&r<=SIZE&&c>=1&&c<=SIZE; }

	private List<int[]> slides(int[] b, int r, int c) {
		List<int[]> list = new ArrayList<>();
		for (int d=0;d<8;d++) {
			int nr=r+DR[d], nc=c+DC[d];
			while (inBounds(nr,nc) && b[flat(nr,nc)]==0) {
				list.add(new int[]{nr,nc});
				nr+=DR[d]; nc+=DC[d];
			}
		}
		return list;
	}

	private List<int[]> generateMoves(int[] b, int color) {
		List<int[]> moves = new ArrayList<>();
		for (int i=0;i<SIZE*SIZE;i++) {
			if (b[i]!=color) continue;
			int r=row(i), c=col(i);
			for (int[] qd : slides(b,r,c)) {
				b[flat(r,c)]=0; b[flat(qd[0],qd[1])]=color;
				for (int[] ad : slides(b,qd[0],qd[1]))
					moves.add(new int[]{r,c,qd[0],qd[1],ad[0],ad[1]});
				b[flat(r,c)]=color; b[flat(qd[0],qd[1])]=0;
			}
		}
		return moves;
	}

	// =========================================================================
	//  MCTS
	// =========================================================================
	private void makeMCTSMove() {
		System.out.println("[MCTS] Thinking... color=" + myColor + (myColor==BLACK?" (BLACK)":" (WHITE)"));
		mctsStart = System.currentTimeMillis();
		int[] best = mcts(board.clone(), myColor);
		if (best == null) { System.out.println("[MCTS] No moves — game over"); return; }

		board[flat(best[0],best[1])] = 0;
		board[flat(best[2],best[3])] = myColor;
		board[flat(best[4],best[5])] = ARROW;
		sendMove(best);

		System.out.printf("[MCTS] queen (%d,%d)->(%d,%d) arrow->(%d,%d) in %dms%n",
				best[0],best[1],best[2],best[3],best[4],best[5],
				System.currentTimeMillis()-mctsStart);

		if (gamegui!=null) gamegui.updateGameState(buildMoveMap(best));
	}

	private static class Node {
		int[]       move;
		int         color;
		Node        parent;
		List<Node>  children = new ArrayList<>();
		List<int[]> untried;
		double      wins=0;
		int         visits=0;
		int[]       snap;

		Node(int[] snap, int color, int[] move, Node parent) {
			this.snap=snap.clone(); this.color=color;
			this.move=move; this.parent=parent;
		}
		double uct(double c) {
			if (visits==0) return Double.MAX_VALUE;
			return wins/visits + c*Math.sqrt(Math.log(parent.visits)/visits);
		}
	}

	private int[] mcts(int[] rootBoard, int rootColor) {
		List<int[]> rootMoves = generateMoves(rootBoard, rootColor);
		if (rootMoves.isEmpty()) return null;
		if (rootMoves.size()==1) return rootMoves.get(0);

		Node root    = new Node(rootBoard, rootColor, null, null);
		root.untried = new ArrayList<>(rootMoves);
		Collections.shuffle(root.untried);

		int iters = 0;
		while (System.currentTimeMillis()-mctsStart < TIME_LIMIT_MS) {
			Node node = select(root);
			if (node.untried!=null && !node.untried.isEmpty()) node = expand(node);
			double result = simulate(node.snap, node.color);
			backprop(node, result);
			iters++;
		}
		System.out.println("[MCTS] iterations=" + iters);

		return root.children.stream()
				.max(Comparator.comparingInt(n->n.visits))
				.map(n->n.move)
				.orElse(rootMoves.get(0));
	}

	private Node select(Node node) {
		while (node.untried!=null && node.untried.isEmpty() && !node.children.isEmpty())
			node = node.children.stream()
					.max(Comparator.comparingDouble(n->n.uct(1.41)))
					.orElse(node.children.get(0));
		return node;
	}

	private Node expand(Node node) {
		int[] move = node.untried.remove(node.untried.size()-1);
		int[] nb   = applyMoveToBoard(node.snap, move, node.color);
		int   next = opp(node.color);
		Node child = new Node(nb, next, move, node);
		child.untried = new ArrayList<>(generateMoves(nb, next));
		Collections.shuffle(child.untried);
		node.children.add(child);
		return child;
	}

	private double simulate(int[] snap, int color) {
		int[]  b   = snap.clone();
		int   turn = color;
		Random rng = new Random();
		for (int d=0;d<30;d++) {
			if (System.currentTimeMillis()-mctsStart >= TIME_LIMIT_MS) return evalBoard(b);
			List<int[]> moves = generateMoves(b, turn);
			if (moves.isEmpty()) return turn==myColor ? 0.0 : 1.0;
			b    = applyMoveToBoard(b, pickMove(b, moves, turn, rng), turn);
			turn = opp(turn);
		}
		return evalBoard(b);
	}

	private int[] pickMove(int[] b, List<int[]> moves, int color, Random rng) {
		if (rng.nextDouble()<0.8) {
			int[] best=null; double bs=Double.NEGATIVE_INFINITY;
			int sample=Math.min(moves.size(),20);
			for (int i=0;i<sample;i++) {
				int[] m=moves.get(rng.nextInt(moves.size()));
				double s=territoryDiff(applyMoveToBoard(b,m,color),color);
				if (s>bs) { bs=s; best=m; }
			}
			return best;
		}
		return moves.get(rng.nextInt(moves.size()));
	}

	private void backprop(Node node, double result) {
		while (node!=null) { node.visits++; node.wins+=result; node=node.parent; }
	}

	private int[] applyMoveToBoard(int[] b, int[] m, int color) {
		int[] nb=b.clone();
		nb[flat(m[0],m[1])]=0;
		nb[flat(m[2],m[3])]=color;
		nb[flat(m[4],m[5])]=ARROW;
		return nb;
	}

	private int opp(int c) { return c==BLACK ? WHITE : BLACK; }

	// ── evaluation ────────────────────────────────────────────────────────────
	private double evalBoard(int[] b) {
		return 0.5 + territoryDiff(b, myColor) / (2.0 * SIZE * SIZE);
	}

	private double territoryDiff(int[] b, int color) {
		int[] md=bfsDist(b,color), od=bfsDist(b,opp(color));
		double mine=0, theirs=0;
		for (int i=0;i<SIZE*SIZE;i++) {
			if (b[i]!=0) continue;
			if      (md[i]<od[i]) mine++;
			else if (od[i]<md[i]) theirs++;
		}
		return mine-theirs;
	}

	private int[] bfsDist(int[] b, int color) {
		int[] dist=new int[SIZE*SIZE]; Arrays.fill(dist, Integer.MAX_VALUE);
		Queue<Integer> q=new ArrayDeque<>();
		for (int i=0;i<SIZE*SIZE;i++) if (b[i]==color) { dist[i]=0; q.add(i); }
		while (!q.isEmpty()) {
			int cur=q.poll(); int r=row(cur), c=col(cur);
			for (int d=0;d<8;d++) {
				int nr=r+DR[d], nc=c+DC[d];
				while (inBounds(nr,nc) && b[flat(nr,nc)]==0) {
					int ni=flat(nr,nc);
					if (dist[ni]==Integer.MAX_VALUE) { dist[ni]=dist[cur]+1; q.add(ni); }
					nr+=DR[d]; nc+=DC[d];
				}
			}
		}
		return dist;
	}

	// =========================================================================
	//  Networking
	// =========================================================================
	private void sendMove(int[] m) { gameClient.sendMoveMessage(buildMoveMap(m)); }

	private Map<String,Object> buildMoveMap(int[] m) {
		Map<String,Object> msg=new HashMap<>();
		msg.put(AmazonsGameMessage.QUEEN_POS_CURR, toList(m[0],m[1]));
		msg.put(AmazonsGameMessage.QUEEN_POS_NEXT, toList(m[2],m[3]));
		msg.put(AmazonsGameMessage.ARROW_POS,      toList(m[4],m[5]));
		return msg;
	}

	private ArrayList<Integer> toList(int r,int c) { return new ArrayList<>(Arrays.asList(r,c)); }

	// =========================================================================
	//  Debug
	// =========================================================================
	private void printBoard() {
		System.out.println("  1 2 3 4 5 6 7 8 9 10");
		for (int r=1;r<=SIZE;r++) {
			System.out.printf("%2d ",r);
			for (int c=1;c<=SIZE;c++) {
				int v=board[flat(r,c)];
				System.out.print(v==0?". ":v==BLACK?"B ":v==WHITE?"W ":"X ");
			}
			System.out.println();
		}
	}

	// =========================================================================
	//  GamePlayer boilerplate
	// =========================================================================
	@Override public String      userName()      { return userName; }
	@Override public GameClient  getGameClient() { return gameClient; }
	@Override public BaseGameGUI getGameGUI()    { return gamegui; }
}