package com.gooserelay.gooserelayvpn.ui.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.ui.theme.GooseRelayVPNTheme
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor
import com.gooserelay.gooserelayvpn.ui.theme.MdvSpace
import com.gooserelay.gooserelayvpn.util.QrCodeUtil
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScannerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SCAN_RESULT = "com.gooserelay.gooserelayvpn.SCAN_RESULT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GooseRelayVPNTheme {
                ScannerScreen(
                    onBackClick = { finish() },
                    onScanResult = { text ->
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SCAN_RESULT, text))
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerScreen(
    onBackClick: () -> Unit,
    onScanResult: (String) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var permissionAttempts by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) permissionAttempts += 1
    }

    fun requestCameraPermission() {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            requestCameraPermission()
        }
    }

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var hasTorch by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var cameraBindFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val decoded = QrCodeUtil.decodeFromUri(context, uri)
                withContext(Dispatchers.Main) {
                    if (!decoded.isNullOrBlank()) {
                        onScanResult(decoded)
                    } else {
                        Toast.makeText(context, context.getString(R.string.scanner_decoding_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scanner_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    if (hasTorch) {
                        IconButton(onClick = {
                            torchEnabled = !torchEnabled
                            cameraControl?.enableTorch(torchEnabled)
                        }) {
                            Icon(
                                if (torchEnabled) Icons.Filled.FlashOff else Icons.Filled.FlashOn,
                                contentDescription = stringResource(if (torchEnabled) R.string.scanner_torch_off else R.string.scanner_torch_on)
                            )
                        }
                    }
                    IconButton(onClick = { galleryLauncher.launch("image/*") }) {
                        Icon(Icons.Filled.Image, contentDescription = stringResource(R.string.scanner_select_image))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MdvColor.OverlayScrim,
                    titleContentColor = MdvColor.OnCameraBackdrop,
                    navigationIconContentColor = MdvColor.OnCameraBackdrop,
                    actionIconContentColor = MdvColor.OnCameraBackdrop
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MdvColor.CameraBackdrop)
        ) {
            if (hasCameraPermission) {
                CameraPreviewView(
                    lifecycleOwner = lifecycleOwner,
                    onScanResult = onScanResult,
                    onCameraReady = { control, info ->
                        cameraControl = control
                        hasTorch = info.hasFlashUnit()
                    },
                    onBindFailed = { cameraBindFailed = true }
                )
                if (cameraBindFailed) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(MdvSpace.S4),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.scanner_camera_unavailable),
                            color = MdvColor.OnCameraBackdrop,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    ScannerOverlay()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(MdvSpace.S4),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.scanner_camera_permission_required),
                        color = MdvColor.OnCameraBackdrop,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(MdvSpace.S3))
                    Button(onClick = {
                        if (permissionAttempts >= 2) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:" + context.packageName))
                            )
                        } else {
                            requestCameraPermission()
                        }
                    }) {
                        Text(
                            stringResource(
                                if (permissionAttempts >= 2) R.string.scanner_open_settings
                                else R.string.scanner_grant_permission
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerOverlay() {
    val scanBoxSize = 260.dp
    val cornerLength = 28.dp
    val cornerWidth = MdvSpace.S1
    val cornerColor = MdvColor.PrimaryContainer

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val boxSizePx = scanBoxSize.toPx()
            val left = (canvasWidth - boxSizePx) / 2f
            val top = (canvasHeight - boxSizePx) / 2f

            drawRect(color = MdvColor.OverlayScrim)
            drawRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(boxSizePx, boxSizePx),
                blendMode = BlendMode.Clear
            )
        }

        Box(
            modifier = Modifier
                .size(scanBoxSize)
                .align(Alignment.Center)
        ) {
            listOf(
                Alignment.TopStart,
                Alignment.TopEnd,
                Alignment.BottomStart,
                Alignment.BottomEnd
            ).forEach { align ->
                Box(
                    modifier = Modifier
                        .align(align)
                        .width(cornerLength)
                        .height(cornerWidth)
                        .background(cornerColor)
                )
                Box(
                    modifier = Modifier
                        .align(align)
                        .width(cornerWidth)
                        .height(cornerLength)
                        .background(cornerColor)
                )
            }
        }

        Text(
            text = stringResource(R.string.scanner_hint),
            color = MdvColor.OnCameraBackdrop,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = scanBoxSize / 2 + 28.dp)
        )
    }
}

@Composable
private fun CameraPreviewView(
    lifecycleOwner: LifecycleOwner,
    onScanResult: (String) -> Unit,
    onCameraReady: (CameraControl, CameraInfo) -> Unit,
    onBindFailed: () -> Unit
) {
    val isResultHandled = remember { AtomicBoolean(false) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var activeCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                activeCameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    activeCameraProvider = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(analysisExecutor) { imageProxy ->
                                processImageProxy(ctx, imageProxy, isResultHandled, onScanResult)
                            }
                        }

                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                    onCameraReady(camera.cameraControl, camera.cameraInfo)
                } catch (_: Exception) {
                    onBindFailed()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun processImageProxy(
    context: android.content.Context,
    imageProxy: ImageProxy,
    isResultHandled: AtomicBoolean,
    onScanResult: (String) -> Unit
) {
    if (isResultHandled.get()) {
        imageProxy.close()
        return
    }
    try {
        val yPlane = imageProxy.planes[0]
        val buffer = yPlane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val source = PlanarYUVLuminanceSource(
            bytes,
            yPlane.rowStride,
            imageProxy.height,
            0,
            0,
            imageProxy.width,
            imageProxy.height,
            false
        )
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        val text = try {
            QRCodeReader().decode(binaryBitmap, hints).text?.trim().orEmpty()
        } catch (_: Exception) {
            try {
                val invertedBitmap = BinaryBitmap(HybridBinarizer(source.invert()))
                QRCodeReader().decode(invertedBitmap, hints).text?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
        if (text.isNotEmpty() && isResultHandled.compareAndSet(false, true)) {
            ContextCompat.getMainExecutor(context).execute {
                onScanResult(text)
            }
        }
    } catch (_: Exception) {
    } finally {
        imageProxy.close()
    }
}
