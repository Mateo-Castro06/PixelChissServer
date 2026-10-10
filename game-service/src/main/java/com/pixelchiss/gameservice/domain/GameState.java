package com.pixelchiss.gameservice.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.*;

/** Mutable aggregate, only mutated on an isolated copy before an atomic repository commit. */
public class GameState {
    public String id;
    public String lobbyId;
    public String creatorPlayerId;
    public String mapId;
    public String creationFingerprint;
    public long version;
    public String status = "ACTIVE";
    public String winnerId;
    public List<Player> players = new ArrayList<>();
    public List<Piece> pieces = new ArrayList<>();
    public Turn turn = new Turn();
    public PendingTrivia trivia;
    public PendingCard card;
    public LinkedHashMap<String, Receipt> receipts = new LinkedHashMap<>();

    public static class Player {
        public String id;
        public int seat;
        public String color;
        public boolean bot;
        public boolean retired;
        public Long disconnectedAt;
        public int turnsStarted;
        public List<Effect> effects = new ArrayList<>();
        public Player() {}
        public Player(String id, int seat, boolean bot) {
            this.id = id; this.seat = seat; this.bot = bot;
            color = List.of("RED", "BLUE", "GREEN", "YELLOW").get(seat);
        }
    }
    public static class Piece {
        public String id;
        public String ownerId;
        public int number;
        public String status = "HOME";
        public int progress = -1;
        public Integer position;
        public Piece() {}
        public Piece(String id, String ownerId, int number) {
            this.id = id; this.ownerId = ownerId; this.number = number;
        }
        public void home() { status = "HOME"; progress = -1; position = null; }
    }
    public static class Turn {
        public String playerId;
        public long number = 1;
        public long deadline;
        public boolean rolled;
        public boolean doubles;
        public int maxDieValue = 6;
        public boolean diceSelectionRequired;
        public int[] dice = new int[0];
        public boolean[] consumed = new boolean[2];
    }
    public static class Effect {
        public String code;
        public int remainingTurns;
        public int startsAtTurn;
        public int maxValue;
        public Effect() {}
        // Persisted field names differ from this convenience constructor's parameters.
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        public Effect(String code, int turns, int start, int max) {
            this.code = code; remainingTurns = turns; startsAtTurn = start; maxValue = max;
        }
    }
    public static class PendingTrivia {
        public String id;
        public String questionId;
        public String playerId;
        public String prompt;
        public List<String> options;
        public long deadline;
    }
    public static class PendingCard {
        public String id;
        public String cardId;
        public String name;
        public String description;
        public String effectCode;
        public String playerId;
        public long deadline;
        public List<CardChoice> choices = new ArrayList<>();
    }
    public record CardChoice(String pieceId,String targetPieceId,String targetPlayerId,Integer targetPosition) {}
    public record Receipt(String fingerprint, long version) {}
    public Player player(String id) { return players.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null); }
    public Piece piece(String id) { return pieces.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null); }
    public List<Piece> occupants(int position, String exceptPiece) {
        return pieces.stream()
                .filter(piece -> !piece.id.equals(exceptPiece) && piece.status.equals("BOARD")
                        && Objects.equals(piece.position, position))
                .toList();
    }
}
