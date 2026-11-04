package com.github.mytv.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fullscreen image / video / music viewer used by pushed files and "在电视上打开".
 *
 * Images are decoded with BitmapFactory (sampled, EXIF rotated, 100 MP limit); video
 * and audio use Media3 ExoPlayer with a SurfaceView-backed PlayerView. The page tag is
 * `viewer` for images and `player` for media; the control tags are `viewer:wallpaper`,
 * `viewer:save`, `viewer:delete`, `player:back10`, `player:toggle`, `player:fwd10`.
 */
@Composable
fun ViewerScreen(
    uri: String,
    mime: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSetWallpaper: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onOpenOther: (() -> Unit)? = null,
) {
    val isImage = mime.startsWith("image/")
    if (isImage) {
        ImageViewer(uri, modifier, onSetWallpaper, onSave, onDelete)
    } else {
        PlayerScreen(uri, mime, onClose, modifier, onOpenOther)
    }
}

@Composable
private fun ImageViewer(
    uri: String,
    modifier: Modifier,
    onSetWallpaper: (() -> Unit)?,
    onSave: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    var image by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val bitmap = withContext(Dispatchers.IO) { loadImage(context, uri) }
        if (bitmap == null) {
            failed = true
        } else {
            image = bitmap.asImageBitmap()
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("viewer"),
    ) {
        when {
            image != null -> Image(
                bitmap = image!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            failed -> Text(
                text = stringResource(R.string.viewer_image_error),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { onSetWallpaper?.invoke() }, modifier = Modifier.testTag("viewer:wallpaper")) {
                Text(stringResource(R.string.viewer_wallpaper))
            }
            Button(onClick = { onSave?.invoke() }, modifier = Modifier.testTag("viewer:save")) {
                Text(stringResource(R.string.viewer_save))
            }
            Button(onClick = { onDelete?.invoke() }, modifier = Modifier.testTag("viewer:delete")) {
                Text(stringResource(R.string.viewer_delete))
            }
        }
    }
}

@Composable
private fun PlayerScreen(
    uri: String,
    mime: String,
    onClose: () -> Unit,
    modifier: Modifier,
    onOpenOther: (() -> Unit)?,
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    var playing by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("player"),
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.viewer_media_error), color = Color.White)
                if (onOpenOther != null) {
                    Button(onClick = onOpenOther) {
                        Text(stringResource(R.string.viewer_open_other))
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                },
                modifier = Modifier.testTag("player:back10"),
            ) {
                Text(stringResource(R.string.player_back10))
            }
            Button(
                onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                },
                modifier = Modifier.testTag("player:toggle"),
            ) {
                Text(
                    stringResource(
                        if (playing) R.string.player_pause else R.string.player_play,
                    ),
                )
            }
            Button(
                onClick = {
                    val duration = player.duration
                    val target = player.currentPosition + 10_000L
                    player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
                },
                modifier = Modifier.testTag("player:fwd10"),
            ) {
                Text(stringResource(R.string.player_forward10))
            }
        }
    }
}

private fun loadImage(context: Context, uri: String): Bitmap? {
    return try {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > 100_000_000L) return null
        val maxSide = 4096
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = openStream(context, parsed)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val rotation = rotationOf(context, parsed)
        if (rotation == 0f) {
            decoded
        } else {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }
    } catch (_: Exception) {
        null
    }
}

private fun openStream(context: Context, uri: Uri): InputStream? = try {
    when {
        uri.scheme == null || uri.scheme == "file" -> FileInputStream(File(uri.path ?: return null))
        else -> context.contentResolver.openInputStream(uri)
    }
} catch (_: Exception) {
    null
}

private fun rotationOf(context: Context, uri: Uri): Float = try {
    val exif = when {
        uri.scheme == null || uri.scheme == "file" -> ExifInterface(uri.path ?: return 0f)
        Build.VERSION.SDK_INT >= 24 -> openStream(context, uri)?.use { ExifInterface(it) } ?: return 0f
        else -> return 0f
    }
    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
} catch (_: Exception) {
    0f
}
