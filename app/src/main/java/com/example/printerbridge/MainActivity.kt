package com.example.printerbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.dantsu.escposprinter.textparser.PrinterTextParserImg
import com.example.printerbridge.databinding.ActivityMainBinding
import java.io.InputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var receiptBitmap: Bitmap? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "Permissions are required to scan/connect Bluetooth printers", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()
        handleIncomingIntent(intent)

        binding.btnPrint.setOnClickListener {
            printReceipt()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null && type.startsWith("image/")) {
            val imageUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            if (imageUri != null) {
                val bitmap = loadBitmapFromUri(imageUri)
                if (bitmap != null) {
                    receiptBitmap = bitmap
                    binding.ivReceiptPreview.setImageBitmap(bitmap)
                    binding.ivReceiptPreview.visibility = View.VISIBLE
                    binding.btnPrint.visibility = View.VISIBLE
                    binding.tvInfo.text = "Receipt intercepted successfully. Ready to print."
                    return
                }
            }
        }

        // Default state if launched normally or no image shared
        binding.ivReceiptPreview.visibility = View.GONE
        binding.btnPrint.visibility = View.GONE
        binding.tvInfo.text = "Please share a receipt image from another app to use this printer bridge."
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load receipt image: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun printReceipt() {
        val bitmap = receiptBitmap
        if (bitmap == null) {
            Toast.makeText(this, "No receipt image to print", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth permission is required to print", Toast.LENGTH_SHORT).show()
            checkAndRequestPermissions()
            return
        }

        binding.btnPrint.isEnabled = false
        binding.btnPrint.text = "Printing..."

        thread {
            try {
                val bluetoothConnection = BluetoothPrintersConnections.selectFirstPaired()
                if (bluetoothConnection == null) {
                    runOnUiThread {
                        Toast.makeText(this, "No paired Bluetooth printer found. Please pair your EPPOS printer in system settings.", Toast.LENGTH_LONG).show()
                        binding.btnPrint.isEnabled = true
                        binding.btnPrint.text = "Print to Bluetooth Printer"
                    }
                    return@thread
                }

                // EPPOS 58mm printer configuration: 203 DPI, 48mm printable width, 32 chars per line
                val printer = EscPosPrinter(bluetoothConnection, 203, 48f, 32)

                // Scale bitmap for 58mm thermal print width (384 dots max)
                val targetWidth = 384
                val scaledBitmap = if (bitmap.width > targetWidth) {
                    val targetHeight = ((bitmap.height.toDouble() / bitmap.width.toDouble()) * targetWidth).toInt()
                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                } else {
                    bitmap
                }

                val drawable = BitmapDrawable(resources, scaledBitmap)
                val hexImage = PrinterTextParserImg.bitmapToHexadecimalString(printer, drawable)

                val formattedText = "[C]<img>$hexImage</img>\n\n\n"
                printer.printFormattedText(formattedText)

                runOnUiThread {
                    Toast.makeText(this, "Struk Berhasil Dicetak", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Printing failed: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.btnPrint.isEnabled = true
                    binding.btnPrint.text = "Print to Bluetooth Printer"
                }
            }
        }
    }
}
