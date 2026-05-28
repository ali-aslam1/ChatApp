package com.gowtham.letschat.fragments.myprofile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
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

    private val storageRef = UserUtils.getStorageRef(context)

    private val docuRef = UserUtils.getDocumentRef(context)

    val mobile = MutableLiveData("${mobileData?.country} ${mobileData?.number}")

    val profileUpdateState = MutableLiveData<LoadState>()

    private lateinit var uploadTask: UploadTask

    init {
        Timber.v("FMyProfileViewModel init")
        fetchLatestProfile()
    }

    private fun fetchLatestProfile() {
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

    fun uploadProfileImage(imagePath: Uri) {
        try {
            isUploading.value = true
            val child = storageRef.child("profile_picture_${System.currentTimeMillis()}.jpg")
            if (this::uploadTask.isInitialized && uploadTask.isInProgress)
                uploadTask.cancel()
            uploadTask = child.putFile(imagePath)
            uploadTask.addOnSuccessListener {
                child.downloadUrl.addOnCompleteListener { taskResult ->
                    isUploading.value = false
                    if (taskResult.isSuccessful) {
                        imageUrl.value = taskResult.result.toString()
                    } else {
                        context.toast("Failed to get download URL")
                    }
                }
            }.addOnFailureListener { e ->
                isUploading.value = false
                context.toast(e.message ?: "Upload failed")
            }
        } catch (e: Exception) {
            isUploading.value = false
            e.printStackTrace()
        }
    }

    fun saveChanges(name: String, strAbout: String, image: String) {
        updateProfileData(name.trim(), strAbout.trim(), image)
    }

    private fun updateProfileData(name: String, strAbout: String, image: String) {
        try {
            profileUpdateState.value = LoadState.OnLoading
            val profile = userProfile ?: return
            profile.userName = name
            profile.about = strAbout
            profile.image = image
            profile.updatedAt = System.currentTimeMillis()
            docuRef.set(profile, SetOptions.merge()).addOnSuccessListener {
                context.toast("Profile updated!")
                userProfile = profile
                preference.saveProfile(profile)
                profileUpdateState.value = LoadState.OnSuccess()
            }.addOnFailureListener { e ->
                context.toast(e.message ?: "Update failed")
                profileUpdateState.value = LoadState.OnFailure(e)
            }
        } catch (e: Exception) {
            profileUpdateState.value = LoadState.OnFailure(e)
            e.printStackTrace()
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (this::uploadTask.isInitialized && uploadTask.isInProgress)
            uploadTask.cancel()
    }

}
