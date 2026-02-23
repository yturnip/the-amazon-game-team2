package ubc.cosc322;

import ygraph.ai.smartfox.games.*;

import java.util.*;

public class RandomMoves extends GamePlayer {

	private GameClient  gameClient;
	private BaseGameGUI gamegui;
	private String      userName;
	private String      passwd;

	private boolean   isBlack;
	private boolean   isMyTurn;
	private GameBoard board = new GameBoard();

	public static void main(String[] args) {
		RandomMoves player = new RandomMoves("a", "b");
		if (player.getGameGUI() == null) { player.Go(); }
		else {
			BaseGameGUI.sys_setup();
			java.awt.EventQueue.invokeLater(player::Go);
		}
	}

	public RandomMoves(String userName, String passwd) {
		this.userName = userName;
		this.passwd   = passwd;
		this.gamegui  = new BaseGameGUI(this);
	}

	@Override public void onLogin() {
		userName = gameClient.getUserName();
		if (gamegui != null) gamegui.setRoomInformation(gameClient.getRoomList());
	}

	@Override
	public boolean handleGameMessage(String messageType, Map<String, Object> msgDetails) {

		if (messageType.equals(GameMessage.GAME_STATE_BOARD)) {
			ArrayList<Integer> state = (ArrayList<Integer>) msgDetails.get("game-state");
			if (state != null) {
				board.initFromGameState(state);
				if (gamegui != null) gamegui.setGameState(state);
			}
			return true;
		}

		if (messageType.equals(GameMessage.GAME_ACTION_START)) {
			String blackPlayer = (String) msgDetails.get("player-black");
			String whitePlayer = (String) msgDetails.get("player-white");
			isBlack    = blackPlayer.equals(userName);
			isMyTurn   = isBlack;
			if (isMyTurn) sendMyMove();
			return true;
		}

		if (messageType.equals(GameMessage.GAME_ACTION_MOVE)) {
			ArrayList<Integer> qCurr  = (ArrayList<Integer>) msgDetails.get("queen-position-current");
			ArrayList<Integer> qNext  = (ArrayList<Integer>) msgDetails.get("queen-position-next");
			ArrayList<Integer> arrow  = (ArrayList<Integer>) msgDetails.get("arrow-position");
			board.applyMove(
					new int[]{qCurr.get(0),  qCurr.get(1)},
					new int[]{qNext.get(0),  qNext.get(1)},
					new int[]{arrow.get(0),  arrow.get(1)}
			);
			if (gamegui != null) gamegui.updateGameState(msgDetails);
			sendMyMove();
			return true;
		}

		return true;
	}

	private void sendMyMove() {
		int myPiece = isBlack ? GameBoard.BLACK : GameBoard.WHITE;

		// generateMoves() handles everything — no manual queen iteration needed
		List<int[]> allMoves = board.generateMoves(myPiece);

		if (allMoves.isEmpty()) {
			System.out.println("No valid moves — I lose.");
			return;
		}

		int[] m = allMoves.get((int)(Math.random() * allMoves.size()));
		board.applyPackedMove(m);

		ArrayList<Integer> qFrom  = toList(m[0], m[1]);
		ArrayList<Integer> qTo    = toList(m[2], m[3]);
		ArrayList<Integer> arrowL = toList(m[4], m[5]);

		if (gamegui != null) gamegui.updateGameState(qFrom, qTo, arrowL);
		gameClient.sendMoveMessage(qFrom, qTo, arrowL);
	}

	private ArrayList<Integer> toList(int r, int c) {
		return new ArrayList<>(Arrays.asList(r, c));
	}

	@Override public String      userName()      { return userName; }
	@Override public GameClient  getGameClient() { return gameClient; }
	@Override public BaseGameGUI getGameGUI()    { return gamegui; }
	@Override public void        connect()       { gameClient = new GameClient(userName, passwd, this); }
}
