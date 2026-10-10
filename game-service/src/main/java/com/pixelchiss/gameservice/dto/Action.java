package com.pixelchiss.gameservice.dto;

import java.util.List;

/** Flat, typed WebSocket action. Identity always comes from the authenticated connection. */
public record Action(String type, String gameId, String requestId, Long expectedVersion,
                     String pieceId, Integer dieIndex, Integer dieValue, Integer fromPosition,
                     Integer targetPosition, List<Integer> chosenDice, String challengeId,
                     Integer answerIndex, String targetPieceId, String targetPlayerId) {}
