package com.pixelchiss.gameservice.domain;

import java.util.List;

/** Internal command. The caller supplies its authenticated actor separately. */
public record GameAction(String type, String gameId, String requestId, Long expectedVersion,
                     String pieceId, Integer dieIndex, Integer dieValue, Integer fromPosition,
                     Integer targetPosition, List<Integer> chosenDice, String challengeId,
                     Integer answerIndex, String targetPieceId, String targetPlayerId) {}
