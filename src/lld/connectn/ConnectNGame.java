import java.util.*;

/* 1. Define Player and Move Strategies */

record Player(String name, String character) {
    Player {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(character, "character must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (character.trim().isEmpty() || character.length() != 1) {
            throw new IllegalArgumentException("character must be exactly one character");
        }
    }
}

interface MoveStrategy {
    int chooseColumn(BoardView board, Player me);
}

class HumanMoveStrategy implements MoveStrategy {
    private final Scanner in;
    HumanMoveStrategy(Scanner in) { this.in = in; }
    @Override
    public int chooseColumn(BoardView board, Player me) {
        System.out.print(me.name() + " (" + me.character() + "), choose column: ");
        return in.nextInt();
    }
}

class RandomBotStrategy implements MoveStrategy {
    private final Random random;                       // passed in, so tests repeat: new Random(42)
    RandomBotStrategy(Random random) { this.random = random; }
    @Override
    public int chooseColumn(BoardView board, Player me) {
        List<Integer> valid = new ArrayList<>();
        for (int c = 0; c < board.getCols(); c++) if (!board.isColumnFull(c)) valid.add(c);
        return valid.get(random.nextInt(valid.size()));  // never picks an invalid column
    }
}


// 2. Define custom exceptions (extending RuntimeException makes them unchecked)
class InvalidMoveException extends RuntimeException {
    public InvalidMoveException(String message) { super(message); }
}

class ColumnFullException extends InvalidMoveException {
    public ColumnFullException(String message) { super(message); }
}

class InvalidColumnException extends InvalidMoveException {
    public InvalidColumnException(String message) { super(message); }
}

class GameOverException extends InvalidMoveException{
    public GameOverException(String message) {super(message); }
}


// 3. Define Board View, Board and Winning Strategy

interface BoardView {
    int getRows();
    int getCols();
    String getCell(int r, int c);
    boolean isColumnFull(int col);
}

class Board implements BoardView {
    private final int rows, cols;

    private List<List<String>> board;

    public Board(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        reset();
    }

    public void reset(){
        board = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            List<String> row = new ArrayList<>();
            for (int j = 0; j < cols; j++) {
                row.add(" ");
            }
            board.add(row);
        }
    }

    public void printBoard() {
        System.out.println("Board State: ");
        for (List<String> row : board) {
            for (String cell : row) {
                System.out.print("[" + cell + "]");
            }
            System.out.println(); // Move to the next line after each row
        }
    }

    public int playColumn(int col, String ch) {
        int rowPlayed=rows-1;
        if (col < 0 || col >= cols) {
            throw new InvalidColumnException("Column out of bound");
        }
        if (isColumnFull(col)) {
            throw new ColumnFullException("Column out is full");
        }
        for (int i = rows - 1; i >= 0; i--) {
            if (board.get(i).get(col).equals(" ")) {
                board.get(i).set(col, ch);
                rowPlayed=i;
                break;
            }
        }
        printBoard();
        return rowPlayed;
    }
    
    public boolean checkFull(){
        for(int j = 0; j<cols; j++){
            if(!isColumnFull(j)){
                return false;
            }
        }
        return true;
    }
    
    @Override 
    public String getCell(int i, int j) {
        return board.get(i).get(j);
    }
    
    @Override 
    public int getRows() {
        return rows;
    }
    
    @Override 
    public int getCols() {
        return cols;
    }

    @Override 
    public boolean isColumnFull(int col){
        return !board.get(0).get(col).equals(" ");
    }
}

interface WinningStrategy {
    boolean hasWon(BoardView board, int r, int c, Player player);
    boolean isCompatible(BoardView board);
}

class KLineWinningStrategy implements WinningStrategy {
    private final int k;

    public KLineWinningStrategy(int k) {
        if(k<2){
            throw new IllegalArgumentException("K should be >=2");
        }
        this.k = k;
    }
    
    @Override
    public boolean isCompatible(BoardView board){
        return (k<=Math.max(board.getRows(),board.getCols()));
    }

    @Override
    public boolean hasWon(BoardView board,int r, int c, Player player) {
        return checkAroundCell(board, r, c, player.character());
    }

    private boolean checkAroundCell(BoardView board, int r, int c, String ch) {
        List<List<Integer>> column = new ArrayList<>();
        List<List<Integer>> row = new ArrayList<>();
        ;
        List<List<Integer>> leftDiagonal = new ArrayList<>();
        List<List<Integer>> rightDiagonal = new ArrayList<>();

        // 1. Column check (varying row, fixed col)
        for (int i = Math.max(0, r - k); i <= Math.min(board.getRows() - 1, r + k); i++) {
            column.add(List.of(i, c));
        }

        // 2. Row check (fixed row, varying col)
        for (int j = Math.max(0, c - k); j <= Math.min(board.getCols() - 1, c + k); j++) {
            row.add(List.of(r, j));
        }

        // 3. Left Diagonal (\ direction: row and col change together)
        for (int i = -k; i <= k; i++) {
            int nr = r + i;
            int nc = c + i;
            if (nr >= 0 && nr < board.getRows() && nc >= 0 && nc < board.getCols()) {
                leftDiagonal.add(List.of(nr, nc));
            }
        }

        // 4. Right Diagonal (/ direction: row increases as col decreases)
        for (int i = -k; i <= k; i++) {
            int nr = r + i;
            int nc = c - i;
            if (nr >= 0 && nr < board.getRows() && nc >= 0 && nc < board.getCols()) {
                rightDiagonal.add(List.of(nr, nc));
            }
        }

        return checkLine(board, column, ch) || checkLine(board, row, ch)
                || checkLine(board, leftDiagonal, ch) || checkLine(board, rightDiagonal, ch);
    }

    private boolean checkLine(BoardView board, List<List<Integer>> cells, String ch) {
        int running = 0;
        for (List<Integer> cell : cells) {
            if (board.getCell(cell.get(0), cell.get(1)).equals(ch)) {
                running++;
                if (running == k) {
                    return true;
                }
            } else {
                running = 0;
            }
        }
        return false;
    }
}


// 4. Define Game Status and Game Engine
enum GameStatus{
    WON,
    IN_PROGRESS,
    DRAW
}

class GameEngine {
    private final Board board;
    private final List<Player> players;
    private int currentPlayerIndex;
    private final WinningStrategy winningStrategy;
    private GameStatus gameStatus;

    public GameEngine(int rows, int cols, WinningStrategy winningStrategy, List<Player> players) {
        if (rows < 4 || cols < 4 || players.size() < 2) {
            throw new IllegalArgumentException("Rows and Cols should be>=4 and players>=2");
        }
        this.board = new Board(rows, cols);
        if(!winningStrategy.isCompatible(board)){
            throw new IllegalArgumentException("Winning Strategy not compatible with the given Board");
        }
        Set<String> seen = new HashSet<>();
        for (Player player : players) {
            if (!seen.add(player.character())) {
                throw new IllegalArgumentException("Player characters must be unique.");
            }
        }
        this.winningStrategy = winningStrategy;
        this.players = List.copyOf(players);
        currentPlayerIndex = 0;
        gameStatus = GameStatus.IN_PROGRESS;
    }

    public GameStatus playTurn(int col) {
        checkNotFinished();
        Player currentPlayer = players.get(currentPlayerIndex);
        int rowPlayed=board.playColumn(col, currentPlayer.character());
        if(winningStrategy.hasWon(board, rowPlayed, col, currentPlayer)) {
            System.out.println("The player: " + currentPlayer.name() + " has won! Game Ends.");
            gameStatus = GameStatus.WON;
            return gameStatus;
        }
        if(board.checkFull()){
            System.out.println("The game has drawn! Restart to play again");
            gameStatus = GameStatus.DRAW;
            return gameStatus;
        }
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
        currentPlayer = players.get(currentPlayerIndex);
        System.out.println(
                "Player: " + currentPlayer.name() + "'s turn to play, character :" + currentPlayer.character());
        return gameStatus;
    }

    private void checkNotFinished() {
        if (gameStatus!=GameStatus.IN_PROGRESS) {
            throw new GameOverException("Game has finished. Restart the game to play more");
        }
    }
    
    public GameStatus getStatus(){
        return gameStatus;
    }

    public BoardView getBoardView(){
        return board;
    }

    public Player getCurrentPlayer(){
        return players.get(currentPlayerIndex);
    }

    public Optional<Player> getWinnerPlayer() {
        if (gameStatus == GameStatus.WON) {
            return Optional.of(players.get(currentPlayerIndex));
        }
        return Optional.empty();
    }

    public void restart() {
        board.reset();
        currentPlayerIndex=0;
        gameStatus = GameStatus.IN_PROGRESS;
    }
}

// 5. GameRunner and Main Runner

class GameRunner {
    private final GameEngine engine;
    private final Map<Player, MoveStrategy> controllers;

    GameRunner(GameEngine engine, Map<Player, MoveStrategy> controllers) {
        this.engine = engine;
        this.controllers = Map.copyOf(controllers);
    }

    public GameStatus run() {
        while (engine.getStatus() == GameStatus.IN_PROGRESS) {
            Player p = engine.getCurrentPlayer();
            int col = controllers.get(p).chooseColumn(engine.getBoardView(), p);
            try {
                engine.playTurn(col);
            } catch (InvalidMoveException e) {
                System.out.println("Invalid move: " + e.getMessage() + ". Try again.");  // the human is asked again
            }
        }
        return engine.getStatus();
    }
}

public class ConnectNGame {
    public static void main(String[] args) {
        System.out.println("Hello\n");

        // GameEngine gameEngine = new GameEngine(8, 8, new KLineWinningStrategy(4),
        //         List.of(new Player("Kshitiz", "#"), new Player("Jaiswal", "*")));

        // gameEngine.playTurn(0);
        // gameEngine.playTurn(2);
        // gameEngine.playTurn(0);
        // gameEngine.playTurn(3);
        // gameEngine.playTurn(0);
        // gameEngine.playTurn(2);
        // gameEngine.playTurn(0);

        // gameEngine.restart();

        // gameEngine.playTurn(0);
        // gameEngine.playTurn(2);
        // gameEngine.playTurn(0);
        // gameEngine.playTurn(3);
        // gameEngine.playTurn(0);
        // gameEngine.playTurn(2);
        // gameEngine.playTurn(0);

        Player a = new Player("BotA", "#"), b = new Player("BotB", "*");
        GameEngine engine = new GameEngine(6, 7, new KLineWinningStrategy(4), List.of(a, b));
        new GameRunner(engine, Map.of(a, new RandomBotStrategy(new Random(32)),
                                    b, new RandomBotStrategy(new Random(7)))).run();
        System.out.println(engine.getWinnerPlayer().map(Player::name).orElse("Draw"));
    }
}