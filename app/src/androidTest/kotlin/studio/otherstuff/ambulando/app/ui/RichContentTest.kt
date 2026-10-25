package studio.otherstuff.ambulando.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.rich.RichActions
import studio.otherstuff.ambulando.app.ui.rich.RichBlocks
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.docs.MarkdownBlocks

@RunWith(AndroidJUnit4::class)
class RichContentTest {
    @get:Rule val compose = createComposeRule()

    private val sample = """
        # Plan
        Hello **bold** and `code` with @[Agent](mention:agent:npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg).

        - [x] done item
        - [ ] open item

        > quoted

        ```kotlin
        val x = 1
        ```

        | A | B |
        | --- | --- |
        | 1 | 2 |

        ![remote](https://example.com/a.png)
    """.trimIndent()

    @Test fun rendersBlocksAndRoutesMentions() {
        var opened: Pair<String, String>? = null
        compose.setContent {
            AmbulandoTheme { RichBlocks(MarkdownBlocks.parse(sample), actions = RichActions(openMention = { k, i -> opened = k to i })) }
        }
        compose.onNodeWithText("Plan").assertIsDisplayed()
        compose.onNodeWithTag("rich.code").assertIsDisplayed()
        compose.onNodeWithTag("rich.table").assertIsDisplayed()
        compose.onNodeWithTag("image.remote", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Hello", substring = true).performFirstLinkClick()
        compose.waitForIdle()
        assertEquals("agent" to "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg", opened)
    }
}
