
package ubc.cosc322;

import ygraph.ai.smartfox.games.*;
import ygraph.ai.smartfox.games.amazons.AmazonsGameMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An example illustrating how to implement a GamePlayer
 * @author Yong Gao (yong.gao@ubc.ca)
 * Jan 5, 2021
 *
 */
public class AmazonsGamePlayer extends GamePlayer {

    private GameClient gameClient = null;
    private BaseGameGUI gamegui = null;

    private String userName = null;
    private String passwd = null;

	private boolean isBlack;
	private boolean isWhite;
	private boolean isMyTurn;
	private GameBoard board = new GameBoard();

    /**
     * The main method
     * @param args for name and passwd (current, any string would work)
     */
    public static void main(String[] args) {
    	AmazonsGamePlayer player = new AmazonsGamePlayer("a", "b");

    	if(player.getGameGUI() == null) {
    		player.Go();
    	}
    	else {
    		BaseGameGUI.sys_setup();
            java.awt.EventQueue.invokeLater(new Runnable() {
                public void run() {
                	player.Go();
                }
            });
    	}
    }

    /**
     * Any name and passwd
     * @param userName
      * @param passwd
     */
    public AmazonsGamePlayer(String userName, String passwd) {
    	this.userName = userName;
    	this.passwd = passwd;
    	
    	//To make a GUI-based player, create an instance of BaseGameGUI
    	//and implement the method getGameGUI() accordingly
    	this.gamegui = new BaseGameGUI(this);
    }

    @Override
    public void onLogin() {
    	System.out.println("Congratulations!!! "
    			+ "I am called because the server indicated that the login is successfully");
    	System.out.println("The next step is to find a room and join it: "
    			+ "the gameClient instance created in my constructor knows how!"); 
    	
    	userName = gameClient.getUserName();
    	if(gamegui != null) {
    		gamegui.setRoomInformation(gameClient.getRoomList());
    	}
    }

    @Override
    public boolean handleGameMessage(String messageType, Map<String, Object> msgDetails) {
    	//This method will be called by the GameClient when it receives a game-related message
    	//from the server.
	
    	//For a detailed description of the message types and format, 
    	//see the method GamePlayer.handleGameMessage() in the game-client-api document. 
    	 
    	if (messageType.equals(GameMessage.GAME_STATE_BOARD)) {
			ArrayList<Integer> gameState = (ArrayList<Integer>) msgDetails.get("game-state");
			if (gameState != null) {
				board.initFromGameState(gameState);
				if (gamegui != null) gamegui.setGameState(gameState);
			}
			return true;
        }

		if (messageType.equals(GameMessage.GAME_ACTION_START)) {
			System.out.println("START keys: " + msgDetails.keySet());
			String blackPlayer =  (String) msgDetails.get("player-black");
			String whitePlayer = (String) msgDetails.get("player-white");

			isBlack = blackPlayer.equals(userName);
			isWhite = whitePlayer.equals(userName);

			isMyTurn = blackPlayer.equals(userName);

			if(isMyTurn) {
				sendMyMove();
			}

			return true;
		}

        if (messageType.equals(GameMessage.GAME_ACTION_MOVE)) {
			System.out.println("START keys: " + msgDetails.keySet());
            ArrayList<Integer> queenCurr = (ArrayList<Integer>) msgDetails.get("queen-position-current");
			ArrayList<Integer> queenNext = (ArrayList<Integer>) msgDetails.get("queen-position-next");
			ArrayList<Integer> arrow = (ArrayList<Integer>) msgDetails.get("arrow-position");

			board.applyMove(
					new int[]{queenCurr.get(0), queenCurr.get(1)},
					new int[]{queenNext.get(0), queenNext.get(1)},
					new int[]{arrow.get(0), arrow.get(1)}
			);

			if (gamegui != null) {
                gamegui.updateGameState(msgDetails);
            }

			sendMyMove();
            return true;
        }
        
    	return true;   	
    }

	private void sendMyMove() {
		int myPiece = isBlack ? GameBoard.BLACK : GameBoard.WHITE;

		// Find our queens first
		List<int[]> myQueens = new ArrayList<>();
		for(int row = 1; row <= 10; row++) {
			for(int col = 1; col <= 10; col++) {
				if (board.getBoard()[row][col] == myPiece) {
					myQueens.add(new int[]{row, col});
				}
			}
		}

		// For each queen, find all valid moves and arrow shots
		List<int[][]> allMoves = new ArrayList<>();

		for(int[] queen : myQueens) {
			List<int[]> queenMoves = getValidMoves(queen);
			for (int[] dest : queenMoves) {
				// Temporarily move the queen to find valid arrow shots
				board.getBoard()[dest[0]][dest[1]] = myPiece;
				board.getBoard()[queen[0]][queen[1]] = GameBoard.EMPTY;

				List<int[]> arrowShots = getValidMoves(dest); // arrows move like queens
				for (int[] arrow : arrowShots) allMoves.add(new int[][]{queen, dest, arrow});

				// Undo the temporary move
				board.getBoard()[queen[0]][queen[1]] = myPiece;
				board.getBoard()[dest[0]][dest[1]] = GameBoard.EMPTY;
			}
		}

		if (allMoves.isEmpty()) {
			System.out.println("No valid moves — I lose.");
			return;
		}

		// Pick a random move
		int[][] chosen = allMoves.get((int)(Math.random() * allMoves.size()));
		int[] qFrom = chosen[0];
		int[] qTo   = chosen[1];
		int[] arrow = chosen[2];

		// Apply the move to our own board
		board.applyMove(qFrom, qTo, arrow);

		// Update the GUI
		ArrayList<Integer> qFromList = toArrayList(qFrom);
		ArrayList<Integer> qToList   = toArrayList(qTo);
		ArrayList<Integer> arrowList = toArrayList(arrow);
		if (gamegui != null)
			gamegui.updateGameState(qFromList, qToList, arrowList);

		// Send the move to the server
		gameClient.sendMoveMessage(qFromList, qToList, arrowList);
	}

	// Returns all cells reachable from [r,c] in 8 directions (queen movement)
	private List<int[]> getValidMoves(int[] from) {
		List<int[]> moves = new ArrayList<>();
		int[][] directions = {
				{-1,0},{1,0},{0,-1},{0,1},   // up, down, left, right
				{-1,-1},{-1,1},{1,-1},{1,1}  // diagonals
		};
		for (int[] dir : directions) {
			int r = from[0] + dir[0];
			int c = from[1] + dir[1];
			while (r >= 1 && r <= 10 && c >= 1 && c <= 10
					&& board.getBoard()[r][c] == GameBoard.EMPTY) {
				moves.add(new int[]{r, c});
				r += dir[0];
				c += dir[1];
			}
		}
		return moves;
	}

	// Converts int[]{row, col} to ArrayList<Integer>
	private ArrayList<Integer> toArrayList(int[] pos) {
		ArrayList<Integer> list = new ArrayList<>();
		list.add(pos[0]);
		list.add(pos[1]);
		return list;
	}
    
    @Override
    public String userName() {
    	return userName;
    }

	@Override
	public GameClient getGameClient() {
		// TODO Auto-generated method stub
		return this.gameClient;
	}

	@Override
	public BaseGameGUI getGameGUI() {
		// TODO Auto-generated method stub
		return  this.gamegui;
	}

	@Override
	public void connect() {
		// TODO Auto-generated method stub
    	gameClient = new GameClient(userName, passwd, this);			
	}

 
}//end of class
