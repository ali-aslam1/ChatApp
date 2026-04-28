package com.gowtham.letschat.services

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.hilt.work.HiltWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.CollectionReference
import com.gowtham.letschat.TYPE_NEW_GROUP_MESSAGE
import com.gowtham.letschat.core.GroupMsgSender
import com.gowtham.letschat.core.OnGrpMessageResponse
import com.gowtham.letschat.db.DbRepository
import com.gowtham.letschat.db.data.Group
import com.gowtham.letschat.db.data.GroupMessage
import com.gowtham.letschat.di.GroupCollection
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
class GroupUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @GroupCollection
    val groupCollection: CollectionReference,
    private val dbRepository: DbRepository):
    Worker(appContext, workerParams) {

    private val params=workerParams

    override fun doWork(): Result {
        val stringData=params.inputData.getString(Constants.MESSAGE_DATA) ?: ""
        val message= Json.decodeFromString<GroupMessage>(stringData)

        val url=params.inputData.getString(Constants.MESSAGE_FILE_URI)!!
        val sourceName=getSourceName(message,url)
        val storageRef=UserUtils.getStorageRef(applicationContext)
        val child = storageRef.child("group/${message.groupId}/$sourceName")
            
        val uri = if (url.startsWith("content://") || url.startsWith("file://")) Uri.parse(url) 
                  else Uri.fromFile(File(url))
                  
        val task = child.putFile(uri)

        val countDownLatch = CountDownLatch(1)
        val result= arrayOf(Result.failure())
        task.addOnSuccessListener {
            child.downloadUrl.addOnCompleteListener { taskResult ->
                if (taskResult.isSuccessful) {
                    val downloadUrl = taskResult.result.toString()
                    sendMessage(message, downloadUrl, result, countDownLatch)
                } else {
                    Timber.e("Failed to get download URL for group message: ${taskResult.exception?.message}")
                    result[0] = Result.failure()
                    updateFailureStatus(message)
                    countDownLatch.countDown()
                }
            }.addOnFailureListener { exception ->
                Timber.e(exception, "Failed to get download URL for group message")
                result[0]= Result.failure()
                updateFailureStatus(message)
                countDownLatch.countDown()
            }
        }.addOnFailureListener { exception ->
            Timber.e(exception, "Group file upload to Firebase Storage failed. Path: group/${message.groupId}/")
            result[0] = Result.failure()
            updateFailureStatus(message)
            countDownLatch.countDown()
        }
        
        try {
            if (!countDownLatch.await(60, TimeUnit.SECONDS)) {
                Timber.w("Group upload timed out after 60 seconds")
                task.cancel()
                updateFailureStatus(message)
                return Result.retry()
            }
        } catch (e: InterruptedException) {
            Timber.e(e, "Group upload interrupted")
            updateFailureStatus(message)
            return Result.retry()
        }
        
        return result[0]
    }

    private fun updateFailureStatus(message: GroupMessage) {
        updateMessageStatus(message, 4)
        dbRepository.insertMessage(message)
        
        try {
            val groupData = params.inputData.getString(Constants.GROUP_DATA)
            if (groupData != null) {
                val group = Json.decodeFromString<Group>(groupData)
                groupCollection.document(group.id)
                    .collection("messages")
                    .document(message.createdAt.toString())
                    .update(mapOf("status" to arrayListOf(4)))
                    .addOnSuccessListener {
                        Timber.d("Group message status updated to 4 in Firestore")
                    }
                    .addOnFailureListener { e ->
                        Timber.e(e, "Failed to update group message status in Firestore")
                    }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating Firestore failure status for group message")
        }
    }

    private fun updateMessageStatus(message: GroupMessage, status: Int) {
        if (message.status.isNotEmpty()) {
            message.status[0] = status
        } else {
            Timber.w("GroupMessage status array was empty, initializing with status $status")
            message.status = arrayListOf(status)
        }
    }

    private fun sendMessage(
        message: GroupMessage,imgUrl: String,
        result: Array<Result>,
        countDownLatch: CountDownLatch) {
        val group=Json.decodeFromString<Group>(params.inputData.getString(Constants.GROUP_DATA)!!)
        setUrl(message,imgUrl)
        val messageSender = GroupMsgSender(groupCollection)
        messageSender.sendMessage(message, group, object : OnGrpMessageResponse{
            override fun onSuccess(message: GroupMessage) {
                sendPushToMembers(group,message)
                result[0]= Result.success()
                countDownLatch.countDown()
            }

            override fun onFailed(message: GroupMessage) {
                result[0]= Result.failure()
                updateFailureStatus(message)
                countDownLatch.countDown()
            }
        })
    }

    private fun setUrl(message: GroupMessage, imgUrl: String) {
        if (message.type=="audio")
            message.audioMessage?.uri=imgUrl
        else
            message.imageMessage?.uri=imgUrl
    }

    private fun sendPushToMembers(group: Group, message: GroupMessage) {
        val users = group.members?.filter { it.user.token.isNotEmpty() }?.map {
            it.user.token
            it
        }
        users?.forEach {
            UserUtils.sendPush(
                applicationContext, TYPE_NEW_GROUP_MESSAGE,
                Json.encodeToString(message), it.user.token, it.id
            )
        }
    }

    private fun getSourceName(message: GroupMessage, url: String): String {
        val createdAt=message.createdAt.toString()
        val num=if (createdAt.length >= 5) createdAt.substring(createdAt.length - 5) else createdAt
        val uri = Uri.parse(url)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(
            applicationContext.contentResolver.getType(uri)) ?: 
            if (message.type == "audio") "mp3" else "jpg"
            
        return "${message.type}_$num.$extension"
    }

}
