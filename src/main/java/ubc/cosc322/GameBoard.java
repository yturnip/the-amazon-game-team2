package ubc.cosc322;

import java.util.*;

/*
GameBoard for MCTS V3.
Additions over V2 GameBoard:
1. bfsDist() caching — computed once per board state, invalidated on mutation.
2. applyTempMove() / undoTempMove() — in-place mutation with undo for zero-alloc evaluation.
*/

public class GameBoard {

    public static final int EMPTY = 0, BLACK = 1, WHITE = 2, ARROW = 3;
    public static final int SIZE  = 10;

    private static final int[] DR = {-1,-1,-1, 0, 0, 1, 1, 1};
    private static final int[] DC = {-1, 0, 1,-1, 1,-1, 0, 1};

    private int[] board = new int[SIZE * SIZE];

    private int[] cachedBlackDist = null;
    private int[] cachedWhiteDist = null;

    public static int flat(int r, int c)         { return (r - 1) * SIZE + (c - 1); }
    public static int row(int flatIdx)            { return flatIdx / SIZE + 1; }
    public static int col(int flatIdx)            { return flatIdx % SIZE + 1; }
    public static boolean inBounds(int r, int c)  {
        return r >= 1 && r <= SIZE && c >= 1 && c <= SIZE;
    }

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
        cachedBlackDist = null;
        cachedWhiteDist = null;
    }

    public void applyMove(int[] posFrom, int[] posTo, int[] arrow) {
        int piece = board[flat(posFrom[0], posFrom[1])];
        board[flat(posFrom[0], posFrom[1])] = EMPTY;
        board[flat(posTo[0],   posTo[1])]   = piece;
        board[flat(arrow[0],   arrow[1])]   = ARROW;
        cachedBlackDist = null;
        cachedWhiteDist = null;
    }

    public void applyPackedMove(int[] m) {
        int piece = board[flat(m[0], m[1])];
        board[flat(m[0], m[1])] = EMPTY;
        board[flat(m[2], m[3])] = piece;
        board[flat(m[4], m[5])] = ARROW;
        cachedBlackDist = null;
        cachedWhiteDist = null;
    }

    public GameBoard withPackedMove(int[] m, int color) {
        GameBoard next  = this.copy();
        int       piece = next.board[flat(m[0], m[1])];
        if (piece == EMPTY) piece = color;
        next.board[flat(m[0], m[1])] = EMPTY;
        next.board[flat(m[2], m[3])] = piece;
        next.board[flat(m[4], m[5])] = ARROW;
        return next;
    }

    // Mutates in place, returns undo info. Call undoTempMove immediately after use.
    public int[] applyTempMove(int[] m) {
        int piece     = board[flat(m[0], m[1])];
        int prevDest  = board[flat(m[2], m[3])];
        int prevArrow = board[flat(m[4], m[5])];
        board[flat(m[0], m[1])] = EMPTY;
        board[flat(m[2], m[3])] = piece;
        board[flat(m[4], m[5])] = ARROW;
        cachedBlackDist = null;
        cachedWhiteDist = null;
        return new int[]{piece, prevDest, prevArrow};
    }

    public void undoTempMove(int[] m, int[] undo) {
        board[flat(m[0], m[1])] = undo[0];
        board[flat(m[2], m[3])] = undo[1];
        board[flat(m[4], m[5])] = undo[2];
        cachedBlackDist = null;
        cachedWhiteDist = null;
    }

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

    public List<int[]> generateMoves(int color) {
        int[]       b     = board.clone();
        List<int[]> moves = new ArrayList<>(400);
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

    public int[] bfsDist(int color) {
        if (color == BLACK) {
            if (cachedBlackDist == null) cachedBlackDist = computeBfsDist(BLACK);
            return cachedBlackDist;
        } else {
            if (cachedWhiteDist == null) cachedWhiteDist = computeBfsDist(WHITE);
            return cachedWhiteDist;
        }
    }

    private int[] computeBfsDist(int color) {
        int[]          dist = new int[SIZE * SIZE];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Queue<Integer> q    = new ArrayDeque<>();
        for (int i = 0; i < SIZE * SIZE; i++) {
            if (board[i] == color) { dist[i] = 0; q.add(i); }
        }
        while (!q.isEmpty()) {
            int cur = q.poll();
            int r   = row(cur), c = col(cur);
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

    public double territoryDiff(int color) {
        int    opp  = (color == BLACK) ? WHITE : BLACK;
        int[]  md   = bfsDist(color);
        int[]  od   = bfsDist(opp);
        double mine = 0, theirs = 0;
        for (int i = 0; i < SIZE * SIZE; i++) {
            if (board[i] != EMPTY) continue;
            if      (md[i] < od[i]) mine++;
            else if (od[i] < md[i]) theirs++;
        }
        return mine - theirs;
    }

    public double eval(int myColor) {
        return 0.5 + territoryDiff(myColor) / (2.0 * SIZE * SIZE);
    }

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