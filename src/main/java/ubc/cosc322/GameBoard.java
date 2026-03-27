package ubc.cosc322;

import java.util.*;

/*
 * Central board representation for the Game of Amazons.
 *
 * Internally uses a flat int[100] (0-indexed, row/col are 1-based externally).
 * Exposes a 2D view via getBoard2D() for GUI/legacy code.
 *
 * Piece values: 0=EMPTY  1=BLACK  2=WHITE  3=ARROW
 */

public class GameBoard {

    public static final int EMPTY = 0, BLACK = 1, WHITE = 2, ARROW = 3;
    public static final int SIZE = 10;

    // 8 direction movements (deltaRow, deltaCol)
    private static final int[] DR = {-1,-1,-1, 0, 0, 1, 1, 1};
    private static final int[] DC = {-1, 0, 1,-1, 1,-1, 0, 1};

    // Flat 1D board
    private int[] board = new int[SIZE * SIZE];

    // Zobrist hash table: ZOBRIST[piece][cell] where piece in {1,2,3} (BLACK/WHITE/ARROW).
    // Initialised once statically so all boards share the same random values.
    // XOR-ing these values produces a unique hash for each board state, used by
    // the MCTS transposition table to detect repeated positions across the tree.
    private static final long[][] ZOBRIST = new long[4][SIZE * SIZE];
    static {
        Random rng = new Random(0x322A4A207A6F4272L);
        for (int p = 1; p <= 3; p++)
            for (int i = 0; i < SIZE * SIZE; i++)
                ZOBRIST[p][i] = rng.nextLong();
    }

    // Indexing helpers from 2D -> 1D and vice versa
    public static int flat(int r, int c)         { return (r - 1) * SIZE + (c - 1); }
    public static int row(int flatIdx)            { return flatIdx / SIZE + 1; }
    public static int col(int flatIdx)            { return flatIdx % SIZE + 1; }
    public static boolean inBounds(int r, int c)  {
        return r >= 1 && r <= SIZE && c >= 1 && c <= SIZE;
    }

    // Loads board from the server's 121-element game-state array (row*11+col indexing).
    public void initFromGameState(ArrayList<Integer> state) {
        int size = state.size();
        if (size == 121) {
            for (int r = 1; r <= SIZE; r++)
                for (int c = 1; c <= SIZE; c++)
                    board[flat(r, c)] = state.get(r * 11 + c);
        } else if (size == 100) {
            for (int i = 0; i < 100; i++) board[i] = state.get(i);
        } else {
            System.out.println("Unexpected game-state size: " + size);
        }
    }

    // Apply move from server messages (mutates this board)
    public void applyMove(int[] posFrom, int[] posTo, int[] arrow) {
        int piece = board[flat(posFrom[0], posFrom[1])];
        board[flat(posFrom[0], posFrom[1])] = EMPTY;
        board[flat(posTo[0],   posTo[1])]   = piece;
        board[flat(arrow[0],   arrow[1])]   = ARROW;
    }

    // Apply a packed move {qr, qc, nr, nc, ar, ac} in-place. Used by MCTS internals.
    // Basically the same but this is used for the search tree on a copied board
    public void applyPackedMove(int[] m) {
        int piece = board[flat(m[0], m[1])];
        board[flat(m[0], m[1])] = EMPTY;
        board[flat(m[2], m[3])] = piece;
        board[flat(m[4], m[5])] = ARROW;
    }

    // Returns a new GameBoard with the packed move applied (non-mutating). Used by MCTS tree nodes.
    public GameBoard withPackedMove(int[] m, int color) {
        GameBoard next = this.copy();
        int piece = next.board[flat(m[0], m[1])];
        // Fallback to color if source square is empty — guards against moves
        // generated on a slightly different board state being applied here
        if (piece == EMPTY) piece = color;
        next.board[flat(m[0], m[1])] = EMPTY;
        next.board[flat(m[2], m[3])] = piece;
        next.board[flat(m[4], m[5])] = ARROW;
        return next;
    }

    // Applies a packed move in-place and returns the displaced values so the
    // move can be undone with undoPackedMove — avoids allocating a board copy.
    // Returns int[]{piece, prevTo, prevArrow} for the undo call.
    // Use this pattern for short-lived evaluations (heuristics, pruning checks)
    // where you need the board state temporarily but don't want to keep the copy.
    public int[] applyTempMove(int[] m) {
        int piece    = board[flat(m[0], m[1])];
        int prevTo   = board[flat(m[2], m[3])];
        int prevArrow = board[flat(m[4], m[5])];
        board[flat(m[0], m[1])] = EMPTY;
        board[flat(m[2], m[3])] = piece;
        board[flat(m[4], m[5])] = ARROW;
        return new int[]{piece, prevTo, prevArrow};
    }

    // Undoes a move applied by applyTempMove, restoring the board to its prior state.
    // undo must be the int[] returned by the matching applyTempMove call.
    public void undoTempMove(int[] m, int[] undo) {
        board[flat(m[0], m[1])] = undo[0]; // restore piece to source
        board[flat(m[2], m[3])] = undo[1]; // restore destination
        board[flat(m[4], m[5])] = undo[2]; // restore arrow square
    }

    // Incrementally updates a Zobrist hash for a packed move without recomputing
    // from scratch. XORs out old cell values and XORs in new ones for the 3
    // affected cells — O(1) vs O(100) for a full recompute.
    // undo is the int[] returned by applyTempMove for the same move.
    // XOR is symmetric so the same call works for both apply and undo directions.
    public static long updateHash(long hash, int[] m, int[] undo) {
        int fromIdx  = flat(m[0], m[1]);
        int toIdx    = flat(m[2], m[3]);
        int arrowIdx = flat(m[4], m[5]);
        int piece    = undo[0]; // the moving piece
        // XOR out old state of the 3 cells
        if (piece   != EMPTY) hash ^= ZOBRIST[piece][fromIdx];    // piece was at source
        if (undo[1] != EMPTY) hash ^= ZOBRIST[undo[1]][toIdx];    // whatever was at dest
        if (undo[2] != EMPTY) hash ^= ZOBRIST[undo[2]][arrowIdx]; // whatever was at arrow
        // XOR in new state (source is now EMPTY so nothing to XOR there)
        if (piece   != EMPTY) hash ^= ZOBRIST[piece][toIdx];      // piece moved to dest
        hash ^= ZOBRIST[ARROW][arrowIdx];                          // arrow placed
        return hash;
    }

    // Helper for generating legal moves — operates on a local board copy so
    // this board is NEVER mutated. Fixes board desync in multi-node MCTS trees.
    public List<int[]> slides(int r, int c) {
        List<int[]> list = new ArrayList<>();
        for (int d = 0; d < 8; d++) {
            int nr = r + DR[d], nc = c + DC[d];
            while (inBounds(nr, nc) && board[flat(nr, nc)] == EMPTY) {
                list.add(new int[]{nr, nc});
                nr += DR[d]; nc += DC[d];
            }
        }
        return list;
    }

    // Generates all legal moves for the given color. Each move is packed as
    // int[]{qFromR, qFromC, qToR, qToC, arrowR, arrowC}.
    // Works on a local clone so the original board is never mutated.
    public List<int[]> generateMoves(int color) {
        // Clone board so temporary queen moves during generation don't corrupt
        // this instance — this was the root cause of board desyncs in V2
        int[] b = board.clone();
        List<int[]> moves = new ArrayList<>();
        for (int i = 0; i < SIZE * SIZE; i++) {
            if (b[i] != color) continue;
            int r = row(i), c = col(i);
            for (int[] qd : slidesOn(b, r, c)) {
                b[flat(r, c)]         = EMPTY;
                b[flat(qd[0], qd[1])] = color;
                for (int[] ad : slidesOn(b, qd[0], qd[1]))
                    moves.add(new int[]{r, c, qd[0], qd[1], ad[0], ad[1]});
                b[flat(r, c)]         = color;
                b[flat(qd[0], qd[1])] = EMPTY;
            }
        }
        return moves;
    }

    // Internal slides helper that works on an explicit board array rather than
    // this.board — used by generateMoves to avoid mutating the live board.
    private static List<int[]> slidesOn(int[] b, int r, int c) {
        List<int[]> list = new ArrayList<>();
        for (int d = 0; d < 8; d++) {
            int nr = r + DR[d], nc = c + DC[d];
            while (inBounds(nr, nc) && b[flat(nr, nc)] == EMPTY) {
                list.add(new int[]{nr, nc});
                nr += DR[d]; nc += DC[d];
            }
        }
        return list;
    }

    // BFS distance from all pieces of the given color. dist[i] = minimum queen-moves needed to reach cell i.
    public int[] bfsDist(int color) {
        int[] dist = new int[SIZE * SIZE];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Queue<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < SIZE * SIZE; i++) {
            if (board[i] == color) { dist[i] = 0; q.add(i); }
        }
        while (!q.isEmpty()) {
            int cur = q.poll();
            int r = row(cur), c = col(cur);
            for (int d = 0; d < 8; d++) {
                int nr = r + DR[d], nc = c + DC[d];
                while (inBounds(nr, nc) && board[flat(nr, nc)] == EMPTY) {
                    int ni = flat(nr, nc);
                    if (dist[ni] == Integer.MAX_VALUE) { dist[ni] = dist[cur] + 1; q.add(ni); }
                    nr += DR[d]; nc += DC[d];
                }
            }
        }
        return dist;
    }

    // Territory advantage for 'color': (cells closer to color) - (cells closer to opponent).
    public double territoryDiff(int color) {
        int opp  = (color == BLACK) ? WHITE : BLACK;
        int[] md = bfsDist(color);
        int[] od = bfsDist(opp);
        double mine = 0, theirs = 0;
        for (int i = 0; i < SIZE * SIZE; i++) {
            if (board[i] != EMPTY) continue;
            if      (md[i] < od[i]) mine++;
            else if (od[i] < md[i]) theirs++;
        }
        return mine - theirs;
    }

    // W2 reachability: marks every empty cell a color can reach in exactly one
    // queen slide from any of its queens. Returns a boolean array where true
    // means the color can reach that cell without moving through occupied squares.
    // Unlike W1 (minimum moves), W2 captures *immediate* threats — if your queen
    // can slide to a cell in one move, you own it tactically right now.
    public boolean[] w2Reach(int color) {
        boolean[] reach = new boolean[SIZE * SIZE];
        for (int i = 0; i < SIZE * SIZE; i++) {
            if (board[i] != color) continue;
            int r = row(i), c = col(i);
            // Slide in all 8 directions, marking every empty cell reachable
            for (int d = 0; d < 8; d++) {
                int nr = r + DR[d], nc = c + DC[d];
                while (inBounds(nr, nc) && board[flat(nr, nc)] == EMPTY) {
                    reach[flat(nr, nc)] = true;
                    nr += DR[d]; nc += DC[d];
                }
            }
        }
        return reach;
    }

    // W2 territory difference: (cells only we can reach in 1 move)
    //                        - (cells only opponent can reach in 1 move).
    // Contested cells (both can reach) count as 0 — neither side owns them yet.
    // This is much more tactically accurate than W1 for early/midgame positions
    // because it directly measures who controls each cell *right now*.
    public double territoryDiffW2(int color) {
        int      opp   = (color == BLACK) ? WHITE : BLACK;
        boolean[] mine  = w2Reach(color);
        boolean[] theirs = w2Reach(opp);
        double myCount = 0, oppCount = 0;
        for (int i = 0; i < SIZE * SIZE; i++) {
            if (board[i] != EMPTY) continue;
            if      ( mine[i] && !theirs[i]) myCount++;
            else if (!mine[i] &&  theirs[i]) oppCount++;
        }
        return myCount - oppCount;
    }

    // Scalar board evaluation in [0,1] for use in MCTS backprop.
    // Returns > 0.5 when 'myColor' is winning territorially.
    public double eval(int myColor) {
        return 0.5 + territoryDiff(myColor) / (2.0 * SIZE * SIZE);
    }

    // W2-based eval: uses immediate reachability instead of W1 distance.
    // More accurate for early/midgame where tactical threats matter most.
    public double evalW2(int myColor) {
        return 0.5 + territoryDiffW2(myColor) / (2.0 * SIZE * SIZE);
    }

    // Validity check
    public boolean isValidMove(int r1, int c1, int r2, int c2) {
        int dr = Integer.signum(r2 - r1), dc = Integer.signum(c2 - c1);
        if (dr == 0 && dc == 0) return false;
        if (dr != 0 && dc != 0 && Math.abs(r2-r1) != Math.abs(c2-c1)) return false;
        int r = r1 + dr, c = c1 + dc;
        while (r != r2 || c != c2) {
            if (board[flat(r, c)] != EMPTY) return false;
            r += dr; c += dc;
        }
        return board[flat(r2, c2)] == EMPTY;
    }

    // Direct access to internal flat array — returns a copy to prevent external mutation.
    public int[] getFlat() { return board.clone(); }

    public int[][] getBoard2D() {
        int[][] b2d = new int[SIZE + 1][SIZE + 1];
        for (int r = 1; r <= SIZE; r++)
            for (int c = 1; c <= SIZE; c++)
                b2d[r][c] = board[flat(r, c)];
        return b2d;
    }

    public GameBoard copy() {
        GameBoard gb = new GameBoard();
        gb.board = this.board.clone();
        return gb;
    }

    // Computes the Zobrist hash of the current board state.
    // Each piece on each cell XORs in a pre-generated random value so the
    // hash changes predictably with each move — used by the transposition table.
    public long zobristHash() {
        long h = 0L;
        for (int i = 0; i < SIZE * SIZE; i++)
            if (board[i] != EMPTY) h ^= ZOBRIST[board[i]][i];
        return h;
    }

    public void printBoard() {
        System.out.println("  1 2 3 4 5 6 7 8 9 10");
        for (int r = 1; r <= SIZE; r++) {
            System.out.printf("%2d ", r);
            for (int c = 1; c <= SIZE; c++) {
                int v = board[flat(r, c)];
                System.out.print(v == EMPTY ? ". " : v == BLACK ? "B " : v == WHITE ? "W " : "X ");
            }
            System.out.println();
        }
    }
}