package io.github.stupside.castor.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

private class GlassBackdrop(val layer: GraphicsLayer) {
  var origin by mutableStateOf(Offset.Zero)
}

private val LocalBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** Records only the scenic backplate. Text and controls never enter the blur layer. */
@Composable
fun GlassScene(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val dark = scheme.background.luminance() < 0.2f
  val layer = rememberGraphicsLayer()
  val backdrop = remember(layer) { GlassBackdrop(layer) }
  SideEffect {
    // Android 9–11 retain the same translucent material without the platform blur.
    layer.renderEffect =
      if (Build.VERSION.SDK_INT >= 31) BlurEffect(24f, 24f, TileMode.Clamp) else null
  }
  Box(modifier.onGloballyPositioned { backdrop.origin = it.positionInRoot() }) {
    Box(
      Modifier.matchParentSize()
        .drawWithContent {
          layer.record { this@drawWithContent.drawContent() }
          drawContent()
        }
        .drawWithCache {
          val aqua = if (dark) Color(0xFF125F68) else Color(0xFF8DDCD6)
          val blue = if (dark) Color(0xFF173F60) else Color(0xFFB5D3F0)
          val sand = if (dark) Color(0xFF514336) else Color(0xFFF4DEC0)
          val top =
            Brush.radialGradient(
              listOf(aqua, Color.Transparent),
              Offset(size.width * .05f, size.height * .18f),
              size.maxDimension * .7f,
            )
          val right =
            Brush.radialGradient(
              listOf(blue.copy(alpha = .8f), Color.Transparent),
              Offset(size.width * 1.05f, size.height * .58f),
              size.maxDimension * .62f,
            )
          val bottom =
            Brush.radialGradient(
              listOf(sand.copy(alpha = .65f), Color.Transparent),
              Offset(size.width * .1f, size.height * 1.05f),
              size.maxDimension * .5f,
            )
          onDrawBehind {
            drawRect(scheme.background)
            drawRect(top)
            drawRect(right)
            drawRect(bottom)
          }
        }
    )
    CompositionLocalProvider(
      LocalBackdrop provides backdrop,
      LocalContentColor provides scheme.onBackground,
      content = content,
    )
  }
}

/** One optical material for panels and native controls, with a crisp specular rim. */
@Composable
fun Modifier.glassMaterial(
  shape: Shape = MaterialTheme.shapes.large,
  accent: Boolean = false,
): Modifier {
  val scheme = MaterialTheme.colorScheme
  val dark = scheme.background.luminance() < .2f
  val backdrop = LocalBackdrop.current
  var position by remember { mutableStateOf(Offset.Zero) }
  val fill = if (accent) scheme.primary else scheme.surface
  val opacity = if (accent) .94f else if (dark) .72f else .64f
  val rim =
    Brush.linearGradient(
      listOf(
        Color.White.copy(alpha = if (dark) .44f else .92f),
        Color.White.copy(alpha = .08f),
        Color.White.copy(alpha = .48f),
      )
    )
  return this.onGloballyPositioned { position = it.positionInRoot() }
    .clip(shape)
    .drawWithCache {
      val sheen =
        Brush.linearGradient(
          listOf(
            Color.White.copy(alpha = if (accent) .14f else .22f),
            Color.Transparent,
            Color.White.copy(alpha = .04f),
          )
        )
      onDrawBehind {
        backdrop?.let {
          val delta = it.origin - position
          translate(delta.x, delta.y) { drawLayer(it.layer) }
        }
        drawRect(fill.copy(alpha = opacity))
        drawRect(sheen)
      }
    }
    .border(BorderStroke(1.dp, rim), shape)
}

@Composable
fun GlassPanel(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val material = modifier.glassMaterial()
  if (onClick == null)
    Surface(
      modifier = material,
      color = Color.Transparent,
      contentColor = MaterialTheme.colorScheme.onSurface,
      content = content,
    )
  else
    Surface(
      onClick = onClick,
      modifier = material,
      color = Color.Transparent,
      contentColor = MaterialTheme.colorScheme.onSurface,
      content = content,
    )
}

@Composable
fun GlassButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  prominent: Boolean = false,
  content: @Composable RowScope.() -> Unit,
) {
  val shape = RoundedCornerShape(50)
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.heightIn(min = 48.dp).glassMaterial(shape, prominent && enabled),
    shape = shape,
    colors =
      ButtonDefaults.buttonColors(
        containerColor = Color.Transparent,
        contentColor =
          if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .22f),
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f),
      ),
    content = content,
  )
}

@Composable
fun glassFieldColors(): TextFieldColors =
  OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .15f),
    unfocusedContainerColor = Color.Transparent,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = Color.Transparent,
    errorBorderColor = MaterialTheme.colorScheme.error,
  )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(
  dismiss: () -> Unit,
  // Asked before a swipe, a tap outside or Back closes the sheet; false keeps it open.
  allowDismiss: () -> Boolean = { true },
  content: @Composable ColumnScope.() -> Unit,
) {
  val allow by rememberUpdatedState(allowDismiss)
  // A stable lambda: the sheet state is remembered per confirmValueChange instance.
  val confirm = remember { { value: SheetValue -> value != SheetValue.Hidden || allow() } }
  ModalBottomSheet(
    // Back reports a dismissal even when the vetoed sheet stayed open.
    onDismissRequest = { if (allow()) dismiss() },
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirm),
    sheetMaxWidth = 640.dp,
    containerColor = Color.Transparent,
    scrimColor = MaterialTheme.colorScheme.onBackground.copy(alpha = .3f),
    dragHandle = null,
  ) {
    GlassScene {
      GlassPanel(Modifier.fillMaxWidth()) {
        Column {
          Box(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
          ) {
            Box(
              Modifier.size(36.dp, 5.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
            )
          }
          content()
        }
      }
    }
  }
}

/** A modal prompt in the sheets' material. A tap outside or Back calls [dismiss]. */
@Composable
fun GlassDialog(dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  Dialog(onDismissRequest = dismiss) {
    val shape = MaterialTheme.shapes.large
    GlassScene(Modifier.fillMaxWidth().clip(shape)) {
      GlassPanel(Modifier.fillMaxWidth()) {
        Column(
          Modifier.padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          content = content,
        )
      }
    }
  }
}

@Composable
fun GlassChoice(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = label,
    modifier = Modifier.heightIn(min = 48.dp).glassMaterial(RoundedCornerShape(50), selected),
    shape = RoundedCornerShape(50),
    colors =
      FilterChipDefaults.filterChipColors(
        containerColor = Color.Transparent,
        selectedContainerColor = Color.Transparent,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        labelColor = MaterialTheme.colorScheme.onSurface,
      ),
    border = null,
  )
}
