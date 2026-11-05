package studio.otherstuff.ambulando.app.browser

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.app.R
import studio.otherstuff.ambulando.app.media.ImagePrivacy
import studio.otherstuff.ambulando.app.media.OkHttpAvatarTransport
import studio.otherstuff.ambulando.app.media.readBounded
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.browser.BrowserFavourite
import studio.otherstuff.ambulando.core.browser.BrowserFavouriteException
import studio.otherstuff.ambulando.core.browser.BrowserFavourites
import java.net.URI

/** Nostr Browser's start-page tint, laid over its `voxel-beach.png` at 20%. */
private val StartTint = Color(0xFF291C3D)
private val LetterTile = Color(0xFF513568)
private val LetterInk = Color(0xFFF1E8FF)
private val TitleShadow = Shadow(Color.Black, Offset(0f, 2f), 6f)

/**
 * The new-tab page: saved sites as a grid of icons over Nostr Browser's default background (`voxel-beach.png` from the
 * Nostr Browser app assets, under the same 20% `#291c3d` tint). Tap opens the site in this tab; long-press edits, moves
 * or removes one; **Edit** arranges the whole list. Saving the current page is in the browser's ••• menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowserStartPage(browser: BrowserModel) {
    var editing by remember { mutableStateOf<BrowserFavourite?>(null) }
    var arranging by remember { mutableStateOf(false) }
    val favourites = browser.favourites
    Box(Modifier.fillMaxSize().testTag("browser.start")) {
        Box(Modifier.fillMaxSize().background(StartTint)) {
            Image(painterResource(R.drawable.browser_start_background), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().testTag("browser.start.background"))
            Box(Modifier.fillMaxSize().background(StartTint.copy(alpha = 0.2f)))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize().testTag("browser.favourites.grid"),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    StartButton("Add", Icons.Outlined.Add, "browser.favourites.add", "Add favourite") { editing = BrowserFavourite(title = "", url = "") }
                    if (favourites.isNotEmpty()) StartButton("Edit", Icons.Outlined.Tune, "browser.favourites.edit", "Edit favourites") { arranging = true }
                }
            }
            if (favourites.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Intro() }
            items(favourites, key = { it.id }) { favourite ->
                val index = favourites.indexOf(favourite)
                var menu by remember { mutableStateOf(false) }
                Box {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .combinedClickable(onClick = { browser.openFavourite(favourite) }, onLongClick = { menu = true }, onLongClickLabel = "Favourite options")
                            .padding(vertical = 6.dp)
                            .semantics { contentDescription = "${favourite.title}, ${favourite.host}" }
                            .testTag("browser.favourite"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FavouriteIcon(favourite, 72.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(favourite.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium, shadow = TitleShadow))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit…") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; editing = favourite },
                            modifier = Modifier.testTag("browser.favourite.edit"))
                        if (index > 0) DropdownMenuItem(text = { Text("Move earlier") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) },
                            onClick = { menu = false; browser.moveFavourite(favourite.id, -1) }, modifier = Modifier.testTag("browser.favourite.earlier"))
                        if (index < favourites.size - 1) DropdownMenuItem(text = { Text("Move later") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) },
                            onClick = { menu = false; browser.moveFavourite(favourite.id, 1) }, modifier = Modifier.testTag("browser.favourite.later"))
                        DropdownMenuItem(text = { Text("Remove", color = Amb.colors.destructive) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = Amb.colors.destructive) },
                            onClick = { menu = false; browser.removeFavourite(favourite.id) }, modifier = Modifier.testTag("browser.favourite.remove"))
                    }
                }
            }
        }
    }
    editing?.let { favourite -> FavouriteEditor(browser, favourite) { editing = null } }
    if (arranging) ArrangeFavourites(browser, onEdit = { editing = it }) { arranging = false }
}

/** Light text buttons that read on the photograph. */
@Composable
private fun StartButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(StartTint.copy(alpha = 0.62f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp)
            .semantics { contentDescription = description }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun Intro() {
    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(14.dp)).background(StartTint.copy(alpha = 0.72f)).padding(22.dp)
                .testTag("browser.favourites.empty"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.StarOutline, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Text("Your favourite sites appear here", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                "Open a site, then choose Add to favourites in the ••• menu, or tap Add. Pages open with your Nostr key available through window.nostr; each site asks before it can see your key, and every signature asks you first.",
                color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Site icons fetched for this run of the app (no cookies or credentials; see [OkHttpAvatarTransport]). */
private object SiteIcons {
    private val transport by lazy { OkHttpAvatarTransport() }
    private val cache = LruCache<String, ImageBitmap>(64)
    private val failed = HashSet<String>()

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        if (url in failed) return null
        val bitmap = runCatching {
            val (bytes, _) = transport.fetch(URI(url), BrowserFavourites.MAX_IMAGE_BYTES)
            withContext(Dispatchers.Default) { decode(bytes) }
        }.getOrNull()
        if (bitmap == null) failed.add(url) else cache.put(url, bitmap)
        return bitmap
    }

    fun decode(bytes: ByteArray, maxPixels: Int = BrowserFavourites.ICON_PIXELS): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}

/** A favourite's picture: the user's picture or text, else the site's icon, else the title's first letter. */
@Composable
internal fun FavouriteIcon(favourite: BrowserFavourite, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.22f)
    val picture = remember(favourite.image) { favourite.image?.let { SiteIcons.decode(it) } }
    val site by produceState<ImageBitmap?>(null, favourite.siteIcon, picture, favourite.symbol) {
        value = if (picture == null && favourite.symbol == null) favourite.siteIcon?.let { SiteIcons.load(it) } else null
    }
    Box(modifier.size(size).shadow(6.dp, shape).clip(shape), contentAlignment = Alignment.Center) {
        when {
            picture != null -> Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("favourite.icon.picture"))
            favourite.symbol != null -> Letter(favourite.symbol!!, size, "favourite.icon.symbol")
            site != null -> Box(Modifier.fillMaxSize().background(Color.White)) {
                Image(site!!, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().testTag("favourite.icon.site"))
            }
            else -> Letter(favourite.initial, size, "favourite.icon.letter")
        }
    }
}

@Composable
private fun Letter(text: String, size: Dp, tag: String) {
    Box(Modifier.fillMaxSize().background(LetterTile).testTag(tag), contentAlignment = Alignment.Center) {
        val scale = if (BrowserFavourites.characterCount(text) > 1) 0.32f else 0.46f
        Text(text, color = LetterInk, maxLines = 1, style = TextStyle(fontSize = (size.value * scale).sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(4.dp))
    }
}

/** Name, address and icon of one favourite (new or saved). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavouriteEditor(browser: BrowserModel, original: BrowserFavourite, onDone: () -> Unit) {
    val amb = Amb.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = browser.favourites.none { it.id == original.id }
    var title by remember(original.id) { mutableStateOf(original.title) }
    var address by remember(original.id) { mutableStateOf(original.url) }
    var symbol by remember(original.id) { mutableStateOf(original.symbol.orEmpty()) }
    var image by remember(original.id) { mutableStateOf(original.image) }
    var error by remember(original.id) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val png = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBounded(32 * 1024 * 1024) } ?: error("unreadable")
                    ImagePrivacy.strippedPng(bytes, BrowserFavourites.ICON_PIXELS).takeIf { it.size <= BrowserFavourites.MAX_IMAGE_BYTES }
                }.getOrNull()
            }
            if (png != null) { image = png; error = null } else error = BrowserFavouriteException.Reason.IMAGE.message
        }
    }
    val preview = original.copy(
        title = title, url = BrowserFavourites.address(address) ?: original.url,
        symbol = symbol.trim().ifEmpty { null }, image = image,
    )
    val description = when {
        image != null -> "Your picture"
        symbol.isNotBlank() -> "Your emoji or text"
        original.siteIcon != null -> "The site's icon"
        else -> "The first letter of the name"
    }
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("favourite.editor")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone, modifier = Modifier.testTag("favourite.cancel")) { Text("Cancel") }
                Text(if (isNew) "Add favourite" else "Edit favourite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val url = BrowserFavourites.address(address) ?: run { error = BrowserFavouriteException.Reason.ADDRESS.message; return@TextButton }
                    try {
                        browser.saveFavourite(original.copy(title = title, url = url, symbol = symbol, image = image))
                        onDone()
                    } catch (e: BrowserFavouriteException) { error = e.message }
                }, modifier = Modifier.testTag("favourite.save")) { Text("Save", fontWeight = FontWeight.SemiBold) }
            }
            OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("favourite.name"))
            OutlinedTextField(address, { address = it }, label = { Text("Address") }, singleLine = true, placeholder = { Text("example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("favourite.address"))
            HorizontalDivider()
            Text("Icon", style = MaterialTheme.typography.labelLarge, color = amb.mutedForeground)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("favourite.icon.preview")) {
                FavouriteIcon(preview, 64.dp)
                Column(Modifier.weight(1f)) {
                    Text(description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("favourite.icon.kind"))
                    Text("A picture is shown first, then your emoji or text, then the site's own icon.", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
                }
            }
            OutlinedTextField(symbol, { symbol = it }, label = { Text("Emoji or up to 3 letters") }, singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().testTag("favourite.symbol"))
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth().testTag("favourite.photo")) {
                Icon(Icons.Outlined.Photo, null); Spacer(Modifier.size(8.dp)); Text("Choose picture")
            }
            if (image != null) TextButton(onClick = { image = null }, modifier = Modifier.testTag("favourite.photo.remove")) { Text("Remove picture", color = amb.destructive) }
            error?.let { Text(it, color = amb.destructive, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("favourite.error")) }
            if (!isNew) {
                HorizontalDivider()
                TextButton(onClick = { browser.removeFavourite(original.id); onDone() }, modifier = Modifier.testTag("favourite.remove")) {
                    Text("Remove favourite", color = amb.destructive)
                }
            }
        }
    }
}

/** The whole list: move up or down, remove, or tap to edit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArrangeFavourites(browser: BrowserModel, onEdit: (BrowserFavourite) -> Unit, onDone: () -> Unit) {
    val amb = Amb.colors
    val favourites = browser.favourites
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("browser.favourites.arrange")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Favourites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone, modifier = Modifier.testTag("browser.favourites.done")) { Text("Done", fontWeight = FontWeight.SemiBold) }
            }
            if (favourites.isEmpty()) Text("No favourites yet.", color = amb.mutedForeground, modifier = Modifier.padding(vertical = 12.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(favourites, key = { _, it -> it.id }) { index, favourite ->
                    Row(Modifier.fillMaxWidth().clickable { onEdit(favourite) }.padding(vertical = 6.dp).testTag("browser.favourites.row"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FavouriteIcon(favourite, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(favourite.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = amb.foreground)
                            Text(favourite.host, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
                        }
                        IconButton(onClick = { browser.moveFavourite(favourite.id, -1) }, enabled = index > 0, modifier = Modifier.testTag("browser.favourites.up")) {
                            Icon(Icons.Outlined.KeyboardArrowUp, "Move ${favourite.title} up")
                        }
                        IconButton(onClick = { browser.moveFavourite(favourite.id, 1) }, enabled = index < favourites.size - 1, modifier = Modifier.testTag("browser.favourites.down")) {
                            Icon(Icons.Outlined.KeyboardArrowDown, "Move ${favourite.title} down")
                        }
                        IconButton(onClick = { browser.removeFavourite(favourite.id) }, modifier = Modifier.testTag("browser.favourites.delete")) {
                            Icon(Icons.Outlined.DeleteOutline, "Remove ${favourite.title}", tint = amb.destructive)
                        }
                    }
                }
            }
        }
    }
}
