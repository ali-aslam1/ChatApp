package com.gowtham.letschat.services

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.hilt.work.HiltWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.storage.UploadTask
import com.gowtham.letschat.TYPE_NEW_MESSAGE
import com.gowtham.letschat.core.MessageSender
import com.gowtham.letschat.core.OnMessageResponse
import com.gowtham.letschat.db.DbRepository
import com.gowtham.letschat.db.data.ChatUser
import com.gowtham.letschat.db.data.Message
import com.gowtham.letschat.di.MessageCollection
import com.gowtham.letschat.utils.Constants
import com.gowtham.letschat.utils.UserUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @MessageCollection
    val msgCollection: CollectionReference,
    val dbRepository: DbRepository):
    Worker(appContext, workerParams) {

    private val params=workerParams

    override fun doWork(): Result {
        val stringData = params.inputData.getString(Constants.MESSAGE_DATA) ?: ""
        val message = Json.decodeFromString<Message>(stringData)
        val url = params.inputData.getString(Constants.MESSAGE_FILE_URI)!!
        val docId = params.inputData.getString(Constants.CHAT_ID) ?: ""

        val storageRef = UserUtils.getStorageRef(applicationContext)
        val sourceName = getSourceName(message, url)
        // Path matches storage.rules: /Users/{senderId}/chats/{recipientId}/{fileName}
        // UserUtils.getStorageRef(context) already returns reference to /Users/{myUid}
        val child = storageRef.child("chats/${message.to}/$sourceName")

        val uri = try {
            if (url.startsWith("content://")) {
                Uri.parse(url)
            } else {
                val file = if (url.startsWith("file://")) File(Uri.parse(url).path!!) else File(url)
                if (!file.exists()) {
                    Timber.e("File does not exist: ${file.absolutePath}")
                    updateFailureStatus(message, docId)
                    return Result.failure()
                }
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing file URI: $url")
            updateFailureStatus(message, docId)
            return Result.failure()
        }

        val countDownLatch = CountDownLatch(1)
        val result = arrayOf(Result.failure())
        
        val task = child.putFile(uri)
        
        task.addOnSuccessListener {
            child.downloadUrl.addOnCompleteListener { taskResult ->
                if (taskResult.isSuccessful) {
                    val downloadUrl = taskResult.result?.toString()
                    if (downloadUrl != null) {
                        sendMessage(message, downloadUrl, result, countDownLatch)
                    } else {
                        Timber.e("Download URL is null despite success")
                        updateFailureStatus(message, docId)
                        countDownLatch.countDown()
                    }
                } else {
                    Timber.e("Failed to get download URL: ${taskResult.exception?.message}")
                    updateFailureStatus(message, docId)
                    countDownLatch.countDown()
                }
            }.addOnFailureListener { exception ->
                Timber.e(exception, "Failed to get download URL")
                updateFailureStatus(message, docId)
                countDownLatch.countDown()
            }
        }.addOnFailureListener { exception ->
            Timber.e(exception, "File upload failed for path: ${child.path}")
            updateFailureStatus(message, docId)
            countDownLatch.countDown()
        }

        try {
            val timeoutReached = !countDownLatch.await(60, TimeUnit.SECONDS)
            if (timeoutReached) {
                Timber.w("Upload timeout after 60 seconds for message: ${message.createdAt}, marking for retry")
                task.cancel()
                updateFailureStatus(message, docId)
                return Result.retry()
            }
        } catch (e: InterruptedException) {
            Timber.e(e, "Upload interrupted")
            task.cancel()
            updateFailureStatus(message, docId)
            return Result.retry()
        }

        return result[0]
    }

    private fun updateFailureStatus(message: Message, docId: String) {
        message.status = 4
        dbRepository.insertMessage(message)
        
        if (docId.isNotEmpty()) {
            val failureUpdate = mapOf("status" to 4)
            msgCollection.document(docId)
                .collection("messages")
                .document(message.createdAt.toString())
                .update(failureUpdate)
                .addOnSuccessListener {
                    Timber.d("Firestore status updated to 4 for message ${message.createdAt}")
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to update Firestore status for message ${message.createdAt}")
                }
        }
    }

    private fun getSourceName(message: Message, url: String): String {
        val createdAt = message.createdAt.toString()
        val num = if (createdAt.length >= 5) createdAt.substring(createdAt.length - 5) else createdAt
        
        val extension = try {
            val file = if (url.startsWith("file://")) File(Uri.parse(url).path!!) else File(url)
            if (file.exists()) {
                file.extension.ifEmpty { 
                    getMimeExtension(url) ?: getDefaultExtension(message.type)
                }
            } else {
                getMimeExtension(url) ?: getDefaultExtension(message.type)
            }
        } catch (e: Exception) {
            getDefaultExtension(message.type)
        }
        
        return "${message.type}_$num.$extension"
    }

    private fun getMimeExtension(url: String): String? {
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(
            applicationContext.contentResolver.getType(Uri.parse(url)))
    }

    private fun getDefaultExtension(type: String): String {
        return when (type) {
            "audio" -> "mp3"
            "video" -> "mp4"
            else -> "jpg"
        }
    }

    private fun sendMessage(message: Message,downloadUrl: String,result: Array<Result>,
        countDownLatch: CountDownLatch) {
        val chatUserData = params.inputData.getString(Constants.CHAT_USER_DATA)
        if (chatUserData == null) {
            Timber.e("Chat user data is null")
            result[0] = Result.failure()
            countDownLatch.countDown()
            return
        }
        
        val chatUser = Json.decodeFromString<ChatUser>(chatUserData)
        setUrl(message,downloadUrl)
        val messageSender = MessageSender(
            msgCollection,
            dbRepository,
            chatUser,object : OnMessageResponse{
                override fun onSuccess(message: Message) {
                    dbRepository.insertMessage(message)
                    UserUtils.sendPush(applicationContext, TYPE_NEW_MESSAGE, Json.encodeToString(message)
                        , chatUser.user.token,message.to)
                    result[0]= Result.success()
                    countDownLatch.countDown()
                }

                override fun onFailed(message: Message) {
                    result[0]= Result.failure()
                    dbRepository.insertMessage(message)
                    countDownLatch.countDown()
                }
            }
        )
        messageSender.checkAndSend(message.from, message.to, message)
    }

    private fun setUrl(message: Message, imgUrl: String) {
        if (message.type=="audio")
            message.audioMessage?.uri=imgUrl
        else
            message.imageMessage?.uri=imgUrl
    }
}
