import unittest

from app.domain.start import GameInitialization
from app.models.enums import LobbyStatus, PlayerRole
from app.models.lobby import Lobby, Player


class StartPolicyTest(unittest.TestCase):
    def test_creator_fallback_order_seats_and_tie_candidates_without_framework(self):
        lobby = Lobby("ABCDEF", "host", "departed", LobbyStatus.WAITING, 3,
                      [Player("host", "Host", PlayerRole.HOST),
                       Player("bot", "Bot", PlayerRole.PLAYER, is_bot=True)],
                      map_votes={"classic": 0, "science": 0})
        options = []

        def choose(winners):
            options.extend(winners)
            return winners[-1]

        plan = GameInitialization.select(lobby, "game-id", choose)
        self.assertEqual(options, ["classic", "science"])
        self.assertEqual((plan.creator_id, plan.map_id), ("host", "science"))
        self.assertEqual([(s.player_id, s.number, s.bot) for s in plan.seats],
                         [("host", 0, False), ("bot", 2, True)])
