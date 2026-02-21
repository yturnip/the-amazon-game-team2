package ubc.cosc322;

import java.util.ArrayList;

public class GameBoard {
    public static final int EMPTY = 0, WHITE = 1, BLACK = 2, ARROW = 3;
    private int[][] board = new int[11][11];

    public void initFromGameState(ArrayList<Integer> gameState) {
        int idx = 0;
        for (int row = 1; row <= 10; row++)
            for (int col = 1; col <= 10; col++)
                board[row][col] = gameState.get(idx++);
    }

    public void applyMove(int[] posFrom, int[] posTo, int[] arrow) {
        int piece = board[posFrom[0]][posFrom[1]];
        board[posFrom[0]][posFrom[1]] = EMPTY;
        board[posTo[0]][posTo[1]] = piece;
        board[arrow[0]][arrow[1]] = ARROW;
    }

    public boolean isValidMove(int row1, int col1, int row2, int col2) {
        int drow = Integer.signum(row2 - row1);
        int dcol = Integer.signum(col2 - col1);

        if (drow == 0 && dcol == 0) return false;
        if (drow != 0 && dcol != 0 && Math.abs(row2-row1) != Math.abs(col2-col1)) return false;
        int row = row1 + drow, col = col1 + dcol;
        while (row != row2 || col != col2) {
            if (board[row][col] != EMPTY) return false;
            row += drow;
            col += dcol;
        }
        return board[row2][col2] == EMPTY;
    }

    public int[][] getBoard() {return board;}
}
