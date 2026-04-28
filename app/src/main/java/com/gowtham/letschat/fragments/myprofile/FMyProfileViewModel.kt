package com.gowtham.letschat.fragments.myprofile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.UploadTask
import com.gowtham.letschat.models.UserProfile
import com.gowtham.letschat.utils.LoadState
import com.gowtham.letschat.utils.MPreference
import com.gowtham.letschat.utils.UserUtils
import com.gowtham.letschat.utils.toast
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class FMyProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preference: MPreference
) : ViewModel() {

    private var userProfile = preference.getUserProfile()

    val userName = MutableLiveData(userProfile?.userName)

    val imageUrl = MutableLiveData(userProfile?.image)

    val about = MutableLiveData(userProfile?.about)

    val isUploading = MutableLiveData(false)

    private val mobileData = userProfile?.mobile

    private var docuRef = UserUtils.getDocumentRef(context)

    val mobile = MutableLiveData("${mobileData?.country} ${mobileData?.number}")

    val profileUpdateState = MutableLiveData<LoadState>()

    private var uploadTask: UploadTask? = null

    init {
        Timber.v("FMyProfileViewModel init")
        fetchLatestProfile()
    }

    private fun fetchLatestProfile() {
        verifyDocuRef()
        docuRef.get().addOnSuccessListener { document ->
            if (document != null && document.exists()) {
                val profile = document.toObject(UserProfile::class.java)
                profile?.let {
                    userProfile = it
                    preference.saveProfile(it)
                    userName.value = it.userName
                    imageUrl.value = it.image
                    about.value = it.about
                }
            }
        }.addOnFailureListener { e ->
            Timber.e(e, "Failed to fetch profile")
        }
    }

    /**
     * Verifies that the document reference points to the current user's UID.
     * Updates docuRef if a mismatch is found.
     */
    private fun verifyDocuRef() {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid != null && docuRef.id != currentUid) {
            Timber.w("docuRef ID mismatch! Current docuRef.id: ${docuRef.id}, Auth UID: $currentUid. Updating reference.")
            docuRef = UserUtils.getDocumentRef(context)
        }
    }

    fun uploadProfileImage(imagePath: Uri, isRetry: Boolean = false) {
        try {
            isUploading.value = true
            verifyDocuRef()
            
            val storageRef = UserUtils.getStorageRef(context)
            val fileName = "profile_picture_${System.currentTimeMillis()}.jpg"
            val child = storageRef.child(fileName)

            Timber.d("Starting profile image upload. Path: ${child.path}")

            uploadTask?.let {
                if (it.isInProgress) {
                    Timber.i("Cancelling previous upload task")
                    it.cancel()
                }
            }

            uploadTask = child.putFile(imagePath)
            
            uploadTask?.let { task ->
                task.addOnSuccessListener {
                    Timber.d("Image uploaded successfully, fetching download URL...")
                    
                    // Add a timeout to the download URL retrieval
                    child.downloadUrl.addOnCompleteListener { taskResult ->
                        isUploading.value = false
                        if (taskResult.isSuccessful) {
                            val result = taskResult.result
                            if (result != null) {
                                val newUrl = result.toString()
                                imageUrl.value = newUrl
                                updateProfileData(userName.value ?: "", about.value ?: "", newUrl)
                            } else {
                                val errorMsg = "Download URL result is null"
                                Timber.e(errorMsg)
                                context.toast(errorMsg)
                            }
                        } else {
                            val errorMsg = taskResult.exception?.message ?: "Failed to get download URL"
                            Timber.e(taskResult.exception, "Failed to get profile picture download URL for $fileName")
                            context.toast(errorMsg)
                        }
                    }
                }.addOnFailureListener { e ->
                    isUploading.value = false
                    val errorMsg = e.message ?: "Upload failed"
                    Timber.e(e, "Profile picture upload failed for $fileName. isRetry: $isRetry")
                    
                    if (!isRetry) {
                        Timber.i("Retrying upload once...")
                        uploadProfileImage(imagePath, true)
                    } else {
                        context.toast("$errorMsg. Please try again later.")
                    }
                }
            } ?: run {
                isUploading.value = false
                Timber.e("UploadTask was null after child.putFile(imagePath)")
                context.toast("Failed to initialize upload")
            }
        } catch (e: Exception) {
            isUploading.value = false
            Timber.e(e, "Unexpected exception in uploadProfileImage")
            context.toast("An unexpected error occurred during upload")
        }
    }

    fun saveChanges(name: String, strAbout: String, image: String) {
        updateProfileData(name.trim(), strAbout.trim(), image)
    }

    private fun updateProfileData(name: String, strAbout: String, image: String) {
        try {
            profileUpdateState.value = LoadState.OnLoading
            verifyDocuRef()
            
            val profile = userProfile ?: UserProfile().apply {
                uId = FirebaseAuth.getInstance().currentUser?.uid
                mobile = mobileData
            }
            
            profile.userName = name
            profile.about = strAbout
            profile.image = image
            profile.updatedAt = System.currentTimeMillis()
            
            Timber.d("Updating Firestore profile document: ${docuRef.id}")
            
            docuRef.set(profile, SetOptions.merge()).addOnSuccessListener {
                context.toast("Profile updated!")
                userProfile = profile
                preference.saveProfile(profile)
                profileUpdateState.value = LoadState.OnSuccess()
            }.addOnFailureListener { e ->
                Timber.e(e, "Firestore profile update failed for UID: ${docuRef.id}")
                context.toast(e.message ?: "Update failed")
                profileUpdateState.value = LoadState.OnFailure(e)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error in updateProfileData")
            profileUpdateState.value = LoadState.OnFailure(e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        uploadTask?.let {
            if (it.isInProgress) it.cancel()
        }
    }

}
