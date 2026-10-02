// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import kotlinx.coroutines.flow.map

/**
 * Artwork (DESIGN.md › v1.2 › Artwork and notes). Every art surface is a square on the elevated
 * surface inside a 1 dp hairline outline with the card corners ([ArtFrame]), so photographs read
 * as prints mounted on the instrument. Pictures are decorative: screen readers skip them, the name
 * beside them says what they are. Portraits show in colour unless the Piano tab's "Artwork in
 * black and white" is on ([LocalArtworkMonochrome], [Monochrome]); the roll cards and monograms
 * are in the theme's own greys either way.
 */

/** The black-and-white look (saturation 0), the Leica Monochrom's. */
val Monochrome: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** Whether portraits and photos draw in black and white: the Piano tab's switch, provided by the nav host. */
val LocalArtworkMonochrome = staticCompositionLocalOf { false }

/** Portraits are cropped to the square a little above centre, where faces are. */
private val PortraitAlignment = BiasAlignment(0f, -0.4f)

/**
 * An art surface: square (or [aspect] wide for its height: a channel's card is 1.4), the elevated
 * surface, a 1 dp hairline outline drawn over the picture, the card corners.
 */
@Composable
fun ArtFrame(modifier: Modifier = Modifier, aspect: Float = 1f, content: @Composable BoxScope.() -> Unit = {}) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .aspectRatio(aspect)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(Hairline, LocalHairline.current, shape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * Where a picture is drawn: its own [ArtFrame] ([framed]), or, inside a frame something else
 * draws (a cell of a channel's mosaic, display mode's backdrop), just [modifier]'s box, cropped to
 * it, with nothing of its own around it.
 */
@Composable
private fun ArtSurface(modifier: Modifier, framed: Boolean, content: @Composable BoxScope.() -> Unit) {
    if (framed) {
        ArtFrame(modifier, content = content)
    } else {
        Box(modifier.clipToBounds().clearAndSetSemantics { }, contentAlignment = Alignment.Center, content = content)
    }
}

/**
 * Art for a playlist or a composer that has none: the initial of [name] in the Display style on
 * the elevated surface. The letter is art, not text: it keeps its size at any font scale.
 */
@Composable
fun MonogramTile(name: String, modifier: Modifier = Modifier, framed: Boolean = true) {
    val density = LocalDensity.current
    ArtSurface(modifier, framed) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
            Text(
                Format.initial(name),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The artwork row for [key] as it changes; the value already in memory first, so a row scrolled back into view does not flash its fallback. */
@Composable
fun rememberArtworkRow(key: String): ArtworkEntity? {
    val artwork = LocalContext.current.graph.artwork
    val row by remember(key) { artwork.artwork(key) }.collectAsStateWithLifecycle(initialValue = artwork.peek(key))
    return row
}

/** The picture of the artwork row for [key], decoded for [size]; null while there is none. */
@Composable
fun rememberArtwork(key: String, size: ArtSize): ImageBitmap? {
    val artwork = LocalContext.current.graph.artwork
    val row = rememberArtworkRow(key)?.takeIf { it.imagePath != null }
    val image by produceState(row?.let { artwork.cached(it, size)?.asImageBitmap() }, row?.imagePath, row?.fetchedAt, size) {
        value = row?.let { artwork.bitmap(it, size)?.asImageBitmap() }
    }
    return image
}

/**
 * The picture for [key] in an [ArtFrame] (or unframed, [framed] false), or [fallback] while there
 * is none. Black and white when the person chose that.
 */
@Composable
fun ArtworkImage(
    key: String,
    size: ArtSize,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    alignment: Alignment = PortraitAlignment,
    fallback: @Composable (Modifier) -> Unit,
) {
    val image = rememberArtwork(key, size)
    if (image == null) {
        fallback(modifier)
        return
    }
    ArtSurface(modifier, framed) {
        Image(
            image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            alignment = alignment,
            contentScale = ContentScale.Crop,
            colorFilter = if (LocalArtworkMonochrome.current) Monochrome else null,
        )
    }
}

/**
 * A piece's roll card in an [ArtFrame] (or unframed): its first 20 seconds as perforations. Where the card
 * can't be drawn (the piece's file gone or unreadable) and the piece is named ([title]), the monogram of its
 * title takes its place, so no frame stands empty (v1.10.1 — M28, D5).
 */
@Composable
fun RollCardImage(pieceId: Long, modifier: Modifier = Modifier, framed: Boolean = true, title: String? = null) {
    val card = rememberRollCard(pieceId)
    if (card is RollCardState.Missing && title != null) {
        MonogramTile(title, modifier, framed)
        return
    }
    ArtSurface(modifier, framed) { RollCardPicture(card, Modifier.fillMaxSize()) }
}

/**
 * A composer without a portrait: the roll cards of their first four pieces, two by two, so Bach,
 * Beethoven and Brahms never collapse into three "B" tiles. Two or three pieces repeat to fill the
 * four (two as a checkerboard, so no row simply repeats the other); a single piece shows its card
 * whole rather than four times.
 */
@Composable
fun MosaicTile(pieceIds: List<Long>, modifier: Modifier = Modifier, framed: Boolean = true) {
    ArtSurface(modifier, framed) {
        if (pieceIds.isEmpty()) return@ArtSurface
        Mosaic(pieceIds.size, Modifier.fillMaxSize()) { i, cell -> RollCard(pieceIds[i], cell) }
    }
}

/**
 * [count] pictures two by two, hairlines between them: one fills the whole; two stand as a
 * checkerboard, so no row simply repeats the other; three or four fill the cells in turn. [cell]
 * draws picture i in its cell's modifier. Shared by a composer's roll cards and a channel's card.
 */
@Composable
fun Mosaic(count: Int, modifier: Modifier = Modifier, cell: @Composable (index: Int, modifier: Modifier) -> Unit) {
    if (count <= 0) return
    if (count == 1) {
        cell(0, modifier)
        return
    }
    val hairline = LocalHairline.current
    Column(modifier) {
        for (row in 0 until 2) {
            if (row == 1) Spacer(Modifier.fillMaxWidth().height(Hairline).background(hairline))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (column in 0 until 2) {
                    if (column == 1) Spacer(Modifier.width(Hairline).fillMaxHeight().background(hairline))
                    cell(mosaicIndex(row, column, count), Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
}

/** Which of [count] pictures fills the mosaic's cell at [row], [column]. */
private fun mosaicIndex(row: Int, column: Int, count: Int): Int =
    if (count == 2) (row + column) % 2 else (row * 2 + column) % count

/**
 * A composer's art: their portrait, else the mosaic of their pieces' roll cards, else (no pieces
 * to draw) the monogram of [name]. Unframed ([framed] false) it fills a cell of something else's
 * frame, as on a channel's card.
 */
@Composable
fun ComposerArt(composerKey: String, name: String, size: ArtSize, modifier: Modifier = Modifier, framed: Boolean = true) {
    ArtworkImage(ArtworkEntity.forComposer(composerKey), size, modifier, framed) { frame ->
        val artwork = LocalContext.current.graph.artwork
        val ids by produceState(artwork.peekMosaic(composerKey), composerKey) { value = artwork.mosaicPieces(composerKey) }
        when {
            ids == null -> ArtSurface(frame, framed) { }
            ids!!.isEmpty() -> MonogramTile(name, frame, framed)
            else -> MosaicTile(ids!!, frame, framed)
        }
    }
}

/**
 * A piece's art where it stands for the piece itself (the mini player, the now-playing panel,
 * display mode's backdrop, the piece sheet, a Studio piece's row): its own cover first (v1.12 — M30: a piece
 * Studio made has one, drawn from its music, cropped about its centre), then its composer's portrait, else its
 * own roll card, which is never mistaken for another piece's, else ([title] given) its title's monogram.
 * [composerKey] is the library's ("" when the composer is unknown). Unframed ([framed] false) it fills
 * [modifier]'s box, cropped.
 */
@Composable
fun PieceArt(pieceId: Long, composerKey: String, size: ArtSize, modifier: Modifier = Modifier, framed: Boolean = true, title: String? = null) {
    ArtworkImage(ArtworkEntity.forPiece(pieceId), size, modifier, framed, alignment = Alignment.Center) { frame ->
        ArtworkImage(ArtworkEntity.forComposer(composerKey), size, frame, framed) { RollCardImage(pieceId, it, framed, title) }
    }
}

/**
 * A playlist's cover: the person's photo, else the portrait of its first piece's composer, else
 * the monogram of [name].
 */
@Composable
fun PlaylistCover(playlistId: Long, name: String, size: ArtSize, modifier: Modifier = Modifier) {
    ArtworkImage(ArtworkEntity.forPlaylist(playlistId), size, modifier) { frame ->
        val artwork = LocalContext.current.graph.artwork
        val first by remember(playlistId) { artwork.firstComposerKey(playlistId).map { FirstComposer(it) } }
            .collectAsStateWithLifecycle(initialValue = null)
        val composerKey = first?.key
        when {
            first == null -> ArtFrame(frame)
            composerKey == null -> MonogramTile(name, frame)
            else -> ArtworkImage(ArtworkEntity.forComposer(composerKey), size, frame) { MonogramTile(name, it) }
        }
    }
}

/** Loaded: the composer of a playlist's first piece, or null for an empty playlist. */
private class FirstComposer(val key: String?)

/** A roll card as it comes: being drawn, [Drawn], or [Missing] (the piece's file gone or unreadable: there will be none). */
private sealed interface RollCardState {
    data object Drawing : RollCardState

    class Drawn(val image: ImageBitmap) : RollCardState

    data object Missing : RollCardState
}

/** Piece [pieceId]'s roll card: from memory at once when it is there, else drawn (or read from disk) off the main thread. */
@Composable
private fun rememberRollCard(pieceId: Long): RollCardState {
    val artwork = LocalContext.current.graph.artwork
    val card by produceState<RollCardState>(artwork.cachedRollCard(pieceId)?.let { RollCardState.Drawn(it.asImageBitmap()) } ?: RollCardState.Drawing, pieceId) {
        value = artwork.rollCard(pieceId)?.let { RollCardState.Drawn(it.asImageBitmap()) } ?: RollCardState.Missing
    }
    return card
}

/** The bare roll card, tinted with the secondary content colour on whatever is behind it. */
@Composable
private fun RollCard(pieceId: Long, modifier: Modifier) {
    RollCardPicture(rememberRollCard(pieceId), modifier)
}

@Composable
private fun RollCardPicture(card: RollCardState, modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier) {
        if (card is RollCardState.Drawn) {
            Image(card.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(tint))
        }
    }
}
