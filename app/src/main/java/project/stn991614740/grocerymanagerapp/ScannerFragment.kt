package project.stn991614740.grocerymanagerapp

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.ProgressDialog
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import project.stn991614740.grocerymanagerapp.databinding.FragmentScannerBinding
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.firebase.auth.ktx.auth
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.camera.view.PreviewView
import com.google.firebase.Timestamp
import java.io.File


class ScannerFragment : Fragment() {

    private lateinit var imageCapture: ImageCapture

    private lateinit var progressDialog: ProgressDialog
    private lateinit var textRecognizer: TextRecognizer
    private lateinit var imageIv: ImageView
    private lateinit var expDate: String
    private var imageUri: Uri? = null

    private val cameraProviderFuture by lazy { ProcessCameraProvider.getInstance(requireContext()) }

    private lateinit var datePickerDialog: DatePickerDialog
    private val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    private var _binding: FragmentScannerBinding? = null
    private val binding get() = _binding!!

    private lateinit var cameraPermissions: Array<String>
    private lateinit var storagePermissions: Array<String>

    private val db = Firebase.firestore
    private val auth = Firebase.auth

    private lateinit var outputDirectory: File


    companion object {
        private const val TAG = "ScannerFragment"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bundle = arguments
        if (bundle == null) {
            Log.d("Confirmation", "Fragment 2 didn't receive any info")
            return
        }
        val args = ScannerFragmentArgs.fromBundle(bundle)

        outputDirectory = getOutputDirectory()

        binding.takeImage.visibility = View.GONE

        cameraPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        storagePermissions = arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        progressDialog = ProgressDialog(requireContext())
        progressDialog.setTitle("Please wait")
        progressDialog.setCanceledOnTouchOutside(false)

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        binding.inputImageBtn.setOnClickListener {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    REQUIRED_PERMISSIONS,
                    REQUEST_CODE_PERMISSIONS
                )
            }
        }

        binding.takeImage.setOnClickListener {
            takePhoto()
        }

        val data = args.description
        binding.testText.setText(data)
        val data2 = args.category
        binding.categoryText.setText(data2)
        val data3 = args.image

        binding.AddToFridgeButton.setOnClickListener {

            val catText = binding.testText.text.toString()
            val descText = binding.categoryText.text.toString()
            val expText = binding.expirationText.text.toString()

            val expDate = dateFormat.parse(expText)

            if (expDate == null) {
                showToast("Invalid date format. Please use dd-MM-yyyy.")
                return@setOnClickListener
            }

            val descriptionString = catText
            val categoryString = descText
            val expirationDate = Timestamp(expDate)
            val catImageString = data3

            val userId = auth.currentUser?.uid
            if (userId == null) {
                Log.d(TAG, "Error: no user signed in.")
                return@setOnClickListener
            }

            val dbManager = DatabaseManager(userId)
            if (catImageString != null) {
                dbManager.addFoodItem(
                    category = descText,
                    description = catText,
                    expirationDate = expDate,
                    categoryImage = catImageString,
                    onSuccess = {
                        Log.d(TAG, "Document added successfully.")
                        findNavController().navigate(R.id.action_scannerFragment_to_fridgeFragment)
                    }
                ) { exception ->
                    Log.w(TAG, "Error adding document", exception)
                }
            }
        }

        val calendar = Calendar.getInstance()
        datePickerDialog = DatePickerDialog(requireContext(), { _, year, monthOfYear, dayOfMonth ->
            val selectedDate = Calendar.getInstance()
            selectedDate.set(Calendar.YEAR, year)
            selectedDate.set(Calendar.MONTH, monthOfYear)
            selectedDate.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            binding.expirationText.setText(dateFormat.format(selectedDate.time))
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))

        calendar.add(Calendar.DAY_OF_YEAR, 7)
        binding.expirationText.setText(dateFormat.format(calendar.time))

        binding.expirationBtn.setOnClickListener {
            datePickerDialog.show()
        }
    }

    private fun showInputImageDialog() {
        val popupMenu = PopupMenu(requireContext(), binding.inputImageBtn)
        popupMenu.menu.add(Menu.NONE, 1, 1, "Camera")
        popupMenu. menu.add(Menu.NONE, 2, 2, "Gallery")
        popupMenu.show()
        popupMenu.setOnMenuItemClickListener { menuItem ->
            val id = menuItem.itemId
            if (id == 1) {
                if (!checkCameraPermission()) {
                    requestCameraPermission()
                } else {
                    pickImageCamera()
                }
            } else if (id == 2) {
                if (!checkStoragePermission()) {
                    requestStoragePermission()
                } else {
                    pickImageGallery()
                }
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun checkCameraPermission(): Boolean {
        val cameraResult = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val storageResult = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        return cameraResult && storageResult
    }

    private fun checkStoragePermission(): Boolean {
        val storageResult = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        return storageResult
    }

    private fun requestCameraPermission() {
        requestPermissions(cameraPermissions, 100)
    }

    private fun requestStoragePermission() {
        requestPermissions(storagePermissions, 101)
    }

    private fun pickImageCamera() {
        val values = ContentValues()
        values.put(MediaStore.Images.Media.TITLE, "Image Title")
        values.put(MediaStore.Images.Media.DESCRIPTION, "Image Description")
        imageUri = requireContext().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
        startForResultCamera.launch(cameraIntent)
    }

    private val startForResultCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            val thumbnail = it.data?.extras?.get("data") as Bitmap
            binding.imageIv.setImageBitmap(thumbnail)  // assuming you have defined imageView correctly in your binding
            recognizeText(thumbnail)
        } else {
            showToast("Failed to capture image")
        }
    }

    private fun pickImageGallery() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startForResultGallery.launch(intent)
    }

    private val startForResultGallery = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        imageUri = it.data?.data
        binding.imageIv.setImageURI(imageUri)

        val bitmap = MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, imageUri)
        recognizeText(bitmap)
    }

    private fun startCamera() {
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
            binding.takeImage.visibility = View.VISIBLE
            binding.inputImageBtn.visibility = View.GONE
            binding.imageIv.visibility = View.GONE
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun takePhoto() {
        // Create timestamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Log.d(TAG, msg)

                    // Load the captured image into a Bitmap
                    val bitmap = MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, savedUri)
                    // Now call recognizeText with the captured bitmap
                    recognizeText(bitmap)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }
            }
        )
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            requireContext(), it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun recognizeText(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { text ->
                showToast("I see the iamge")
                // Handle successful text recognition here
                val detectedText = text.text // text.text gives the whole text recognized from image.
                Log.d("RecognizedText", "Recognized Text: $detectedText")
                binding.expirationText.setText(detectedText) // Update expirationText with recognized text
            }
            .addOnFailureListener { exception ->
                // Handle failed text recognition here
                showToast("Text Recognition failed: ${exception.message}")
                Log.d("RecognizedText", "Failed")
            }
    }

    private fun getOutputDirectory(): File {
        val mediaDir = requireContext().externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir
        else
            requireContext().filesDir
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}