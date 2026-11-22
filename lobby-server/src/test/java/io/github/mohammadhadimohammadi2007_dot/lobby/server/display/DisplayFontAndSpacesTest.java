package io.github.mohammadhadimohammadi2007_dot.lobby.server.display;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigManager;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigSnapshot;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.FormattedText;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.Placeholder;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.PlaceholderRegistry;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.PlaceholderService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayFontAndSpacesTest {

    private static final Key ASEPRITE = Key.key("aseprite_fonts", "aseprite");
    private static final String WIDE_SPACES = " ".repeat(60);

    @Test
    void scoreboardAndTabPreserveCustomFontAndWideSpaceLines(@TempDir Path dir) throws Exception {
        ConfigManager manager = new ConfigManager(dir);
        manager.load();

        Files.writeString(dir.resolve(ConfigManager.DISPLAY_FILE), """
                scoreboard:
                  enabled: true
                  boards:
                    default:
                      priority: 0
                      permission: ""
                      title:
                        - "<font:aseprite_fonts:aseprite><gold>LOBBY</font>"
                      title-interval: 20
                      update-interval: 20
                      lines:
                        - "%s"
                        - "<font:aseprite_fonts:aseprite>&aOnline: &f%%server_online%%</font>"
                tab:
                  enabled: true
                  update-interval: 20
                  header:
                    - "<font:aseprite_fonts:aseprite><gold>SERVER</font>"
                    - "%s"
                  footer:
                    - "%s"
                    - "<font:aseprite_fonts:aseprite>&7play.example.net</font>"
                  name-format: "<font:aseprite_fonts:aseprite>%%player_name%%</font>"
                  show: server
                  group-order: [default]
                nametags:
                  enabled: false
                bossbar:
                  enabled: false
                join-title:
                  enabled: false
                action-bar:
                  enabled: false
                """.formatted(WIDE_SPACES, WIDE_SPACES, WIDE_SPACES));

        ConfigSnapshot snapshot = manager.load();
        DisplayConfig display = snapshot.display();

        // 1. Wide space-only lines are preserved in scoreboard lines and tab header/footer.
        SidebarConfig.Board board = display.sidebar().boards().getFirst();
        assertEquals(WIDE_SPACES, board.lines().get(0).text().modern());
        assertTrue(display.tab().header().modern().contains("\n" + WIDE_SPACES),
                "tab.header must preserve space-only lines without trimming or dropping them");
        assertTrue(display.tab().footer().modern().startsWith(WIDE_SPACES + "\n"),
                "tab.footer must preserve space-only lines without trimming or dropping them");

        // 2. <font:aseprite_fonts:aseprite> is preserved even with legacy &a/&f codes and %placeholders%.
        PlaceholderRegistry registry = new PlaceholderRegistry();
        registry.register("server", p -> new Placeholder.Constant("42", false));
        PlaceholderService placeholders = new PlaceholderService(registry);

        Component title = placeholders.render(board.title().getFirst().modern(), null);
        assertTrue(hasFont(title, ASEPRITE), "scoreboard title should carry aseprite_fonts:aseprite");

        Component line = placeholders.render(board.lines().get(1).text().modern(), null);
        assertEquals("Online: 42", PlainTextComponentSerializer.plainText().serialize(line));
        assertTrue(hasFont(line, ASEPRITE), "scoreboard line with &a/&f and placeholder must keep aseprite_fonts:aseprite");

        Component tabHeader = placeholders.render(display.tab().header().modern(), null);
        assertTrue(hasFont(tabHeader, ASEPRITE), "tab header should carry aseprite_fonts:aseprite");
        assertTrue(PlainTextComponentSerializer.plainText().serialize(tabHeader).contains(WIDE_SPACES),
                "rendered tab header must keep all 60 spaces");

        Component tabFooter = placeholders.render(display.tab().footer().modern(), null);
        assertTrue(hasFont(tabFooter, ASEPRITE), "tab footer with &7 must keep aseprite_fonts:aseprite");
    }

    @Test
    void legacyColorInsideFontTagDoesNotResetFont() {
        Component c = FormattedText.parse("<font:aseprite_fonts:aseprite>&l&cHello</font>");
        assertTrue(hasFont(c, ASEPRITE));
    }

    private static boolean hasFont(Component component, Key expected) {
        if (expected.equals(component.font())) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasFont(child, expected)) {
                return true;
            }
        }
        return false;
    }
}
