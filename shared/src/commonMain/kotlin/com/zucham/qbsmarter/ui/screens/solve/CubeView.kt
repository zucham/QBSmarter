package com.zucham.qbsmarter.ui.screens.solve

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zakgof.korender.Korender
import com.zakgof.korender.context.FrameContext
import com.zakgof.korender.math.ColorRGB.Companion.white
import com.zakgof.korender.math.ColorRGBA
import com.zakgof.korender.math.Transform
import com.zakgof.korender.math.Vec3
import com.zakgof.korender.math.x
import com.zakgof.korender.math.y
import com.zucham.qbsmarter.domain.cube.CUBE_PARTS
import com.zucham.qbsmarter.domain.cube.CubePieceData
import com.zucham.qbsmarter.domain.cube.RubiksCube
import com.zucham.qbsmarter.ui.theme.ThemeMode
import qbsmarter.shared.generated.resources.Res

/**
 * 3D cube viewport. Sized by parent so it scales with screen width. Reads
 * the cube's piece transforms each frame; touch events go to the orbiter.
 *
 * Lifecycle gating (the "lingering surface" fix): Korender renders into a
 * native Android SurfaceView that lives in the window layer, *not* in
 * Compose's drawing tree. When the user navigates away, Compose removes
 * the CubeView composable but the SurfaceView itself takes ~1 frame to
 * detach – and during that frame the new screen has already started
 * drawing on top, so the cube briefly shows through. To avoid that we
 * proactively hide the Korender block on Lifecycle.ON_PAUSE / ON_STOP via
 * a [renderActive] flag, and only render it while the screen is in the
 * RESUMED state. The fallback Box keeps the same theme-colored square
 * underneath so there's no visible flash.
 *
 * Initial-frame cover (the "black flash" fix): a fresh SurfaceView
 * starts opaque-black until its OpenGL surface is ready and the first
 * frame is drawn – typically ~600-900ms on a cold start. The
 * CompositionLocal `Modifier.background(theme)` on the Box around the
 * Korender block doesn't help because the SurfaceView is in a SEPARATE
 * window layer above the Compose drawing tree. We work around this by
 * stacking a same-colored cover Box ON TOP of the Korender block for
 * a short window, then fading it out. The cover is exactly the theme
 * background color, so for the period it's visible the user sees a
 * solid square in their theme – visually indistinguishable from "the
 * cube is there but isn't drawn yet". When the cover fades out, the
 * user sees the cube come up smoothly rather than blinking from black.
 */
@Composable
fun CubeView(
    cube: RubiksCube,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    mode: ThemeMode
) {
    val orbiter = cube.orbiter
    val isSystemDarkTheme = isSystemInDarkTheme()

    // True only while the host screen is at least STARTED. Switching to
    // false on ON_PAUSE makes the Korender block leave the composition
    // synchronously on this recomposition pass, which gives the Surface-
    // View time to detach before the navigation transition completes.
    var renderActive by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> renderActive = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> renderActive = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // The disposal itself also flips us off – covers the case of
            // navigation within the same Activity (no lifecycle event,
            // just a NavHost-level dispose).
            renderActive = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // Solid background under the Korender SurfaceView. If Android
            // ever does flash the underlying window between detach and
            // recomposition, the user sees the theme background, not white.
            .background(backgroundColor),
    ) {
        if (renderActive) {
            // Wrap Korender in `key(...)` so a theme change tears down and
            // rebuilds the surface with the new color. Korender's
            // `this.background` is set once during scene setup; assigning
            // to it inside Frame{} doesn't propagate to the GL clear color
            // reliably across versions, so the safest path is a recompose.
            // Theme changes are infrequent – this is not a per-frame cost.
            key(backgroundColor) {
                Korender(appResourceLoader = { Res.readBytes(it) }) {
                    this.background = ColorRGBA(
                        backgroundColor.red,
                        backgroundColor.green, backgroundColor.blue, backgroundColor.alpha,
                    )
                    OnTouch { orbiter.touch(it) }
                    Frame {
                        // Drive time-based cube animation from the render
                        // loop. The gyro smoothing needs a real frame
                        // delta to stay frame-rate independent, and this
                        // is the only place one is available. It must run
                        // before the piece transforms are read below, so
                        // every cubie in this frame sees the same
                        // orientation.
                        //
                        // Safe to call from the render thread: advanceFrame
                        // touches only plain and @Volatile fields, never
                        // Compose state, so a 60-120 Hz tick can't trigger
                        // recomposition.
                        cube.advanceFrame(frameInfo.dt)

                        // Light up the cube based on the current theme (light/dark)
                        val lightIntensity = if (mode == ThemeMode.LIGHT || (mode == ThemeMode.SYSTEM && !isSystemDarkTheme)) 4f else 3f

                        // Four point lights - one along the direction of each axis and the corner closest to camera
                        PointLight(Vec3(10f, 0f, 0f), white(lightIntensity))
                        PointLight(Vec3(0f, 10f, 0f), white(lightIntensity))
                        PointLight(Vec3(0f, 0f, 10f), white(lightIntensity))
                        PointLight(Vec3(10f, 10f, 10f), white(lightIntensity/1.5f))
                        AmbientLight(white(intensity = lightIntensity/10))

                        // FXAA anti-aliasing - cannot be enabled because of a black artifact before the actual model is loaded - too distracting
                        // PostProcess(fxaa())

                        val camPos = Vec3(6f, 5f, 14f)
                        val dir = (Vec3.ZERO - camPos).normalize()
                        val right = (dir % Vec3(0f, 1f, 0f)).normalize()
                        val up = (right % dir).normalize()
                        camera = camera(camPos, dir, up)

                        CUBE_PARTS.forEachIndexed { index, pieceData ->
                            renderPiece(pieceData, cube.pieceTransform(index))
                        }

                        Renderable(
                            base(color = ColorRGBA(0.5f, 0.5f, 0.5f, 1f), metallicFactor = 0f, roughnessFactor = 1f),
                            mesh = sphere(radius = 1.5F),
                            transform = Transform.translate(0.x + 0.y),
                            transparent = false,
                        )
                    }
                }
            }
        } else {
            // Placeholder while paused – same theme color as the background
            // so there's no visible difference between rendered/paused while
            // the user is in the active screen.
            Box(modifier = Modifier.fillMaxSize().background(backgroundColor))
        }
    }
}

private fun FrameContext.renderPiece(data: CubePieceData, transform: Transform) {
    Renderable(
        base(colorTexture = texture("gan_cube_model/multitexture.png")),
        mesh = obj(data.meshFile),
        transform = transform,
    )
}
