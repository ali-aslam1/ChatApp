package com.gowtham.letschat.core

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.google.firebase.firestore.*
import com.gowtham.letschat.FirebasePush
import com.gowtham.letschat.db.DbRepository
import com.gowtham.letschat.db.data.ChatUser
import com.gowtham.letschat.db.data.Message
import com.gowtham.letschat.fragments.single_chat.toDataClass
import com.gowtham.letschat.utils.LogMessage
import com.gowtham.letschat.utils.MPreference
import com.gowtham.letschat.utils.UserUtils
import com.gowtham.letschat.utils.getUnreadCount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dbRepository: DbRepository,
    private val usersCollection: CollectionReference,
    private val preference: MPreference,
    private val messageStatusUpdater: MessageStatusUpdater,
    private val firebaseFirestore: com.google.firebase.firestore.FirebaseFirestore
) {

    private val messagesList: MutableList<Message> by lazy { mutableListOf() }

    private var fromUser = preference.getUid()

    val message = MutableLiveData<String>()

    private lateinit var chatUsers: List<ChatUser>

    private lateinit var messagesCollection: CollectionReference

    private val conversationListeners = HashMap<String, ListenerRegistration>()

    private val chatUserUtil = ChatUserUtil(dbRepository, usersCollection, null)

    init {
        instance = this
    }

    companion object {

        private var listenerDoc1: ListenerRegistration? = null
        private var instanceCreated = false
        private var instance: ChatHandler? = null

        fun removeListeners() {
            instanceCreated = false
            listenerDoc1?.remove()
            listenerDoc1 = null
            instance?.clearConversationListeners()
        }
    }

    fun initHandler() {
        if (instanceCreated)
            return
        instanceCreated = true
        fromUser = preference.getUid()
        Timber.v("ChatHandler init - User: $fromUser")
        if (fromUser.isNullOrBlank()) {
            Timber.e("ChatHandler: User ID is null or blank!")
            return
        }
        messagesCollection = firebaseFirestore.collection("Messages")
        preference.clearCurrentUser()

        listenerDoc1 = messagesCollection.whereArrayContains("chat_members", fromUser!!)
            .addSnapshotListener { snapShots, error ->
                if (error != null) {
                    Timber.e("ChatHandler conversation listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapShots == null) {
                    Timber.w("ChatHandler conversation snapshot is null")
                    return@addSnapshotListener
                }
                Timber.v("ChatHandler: conversation snapshot received ${snapShots.documents.size} docs")
                handleConversationDocs(snapShots)
            }
    }

    private fun handleConversationDocs(snapShots: QuerySnapshot) {
        Timber.v("ChatHandler: conversation snapshot changes count = ${snapShots.documentChanges.size}")
        for (shot in snapShots.documentChanges) {
            if (shot.type == DocumentChange.Type.ADDED || shot.type == DocumentChange.Type.MODIFIED) {
                Timber.v("ChatHandler conversation doc ${shot.document.id}, chat_members=${shot.document.get("chat_members")}")
                listenToConversationMessages(shot.document.id)
            }
        }
    }

    private fun clearConversationListeners() {
        conversationListeners.values.forEach { it.remove() }
        conversationListeners.clear()
    }

    private fun listenToConversationMessages(conversationId: String) {
        if (conversationListeners.containsKey(conversationId)) return
        val listener = messagesCollection.document(conversationId).collection("messages")
            .addSnapshotListener { snapShots, error ->
                if (error != null) {
                    Timber.e("ChatHandler message listener error for $conversationId: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapShots == null) {
                    Timber.w("ChatHandler message snapshot is null for $conversationId")
                    return@addSnapshotListener
                }
                Timber.v("ChatHandler: message snapshot received for $conversationId with ${snapShots.documentChanges.size} changes")
                processMessagesSnapshot(snapShots)
            }
        conversationListeners[conversationId] = listener
    }

    private fun processMessagesSnapshot(snapShots: QuerySnapshot) {
        messagesList.clear()
        for (shot in snapShots.documentChanges) {
            if (shot.type == DocumentChange.Type.ADDED || shot.type == DocumentChange.Type.MODIFIED) {
                try {
                    val document = shot.document
                    val message = document.data.toDataClass<Message>()
                    message.chatUserId = if (message.from != fromUser) message.from else message.to
                    messagesList.add(message)
                    Timber.v("ChatHandler: ${shot.type} message from ${message.from} to ${message.to}")
                } catch (e: Exception) {
                    Timber.e("ChatHandler: Error processing message document: ${e.message}")
                }
            }
        }
        if (messagesList.isNotEmpty()) {
            Timber.v("ChatHandler: inserting ${messagesList.size} messages to DB")
            insertMessageOnDb(messagesList)
        }
    }

    private fun insertMessageOnDb(messages: List<Message>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val contacts = ArrayList<ChatUser>()
                val newContactIds = ArrayList<String>()
                chatUsers = dbRepository.getChatUserList()
                Timber.v("ChatHandler DB: Inserting  messages, existing users: ")
                dbRepository.insertMultipleMessage(messages.toMutableList())
                for (message in messages) {
                    val chatUserId = message.chatUserId ?: continue
                    val chatUser = chatUsers.firstOrNull { it.id == chatUserId }
                    if (chatUser == null) {
                        newContactIds.add(chatUserId)
                        Timber.v("ChatHandler DB: New contact: ")
                    } else {
                        chatUser.unRead = if (preference.getOnlineUser() == chatUser.id) 0 else
                            dbRepository.getChatsOfFriend(chatUser.id).getUnreadCount(chatUser.id)
                        contacts.add(chatUser)
                    }
                }
                dbRepository.insertMultipleUsers(contacts)
                val currentChatUser = if (preference.getOnlineUser().isNotEmpty())
                    contacts.firstOrNull { it.id == preference.getOnlineUser() }
                else null
                val allUnReadMsgs = dbRepository.getAllNotSeenMessage()
                Timber.v("ChatHandler DB: Total unread messages: ")
                withContext(Dispatchers.Main) {
                    updateMsgStatus(newContactIds, currentChatUser, allUnReadMsgs)
                }
            } catch (e: Exception) {
                Timber.e("ChatHandler DB: Error inserting messages: ")
                e.printStackTrace()
            }
        }
    }

    private fun updateMsgStatus(
        newContactIds: ArrayList<String>,
        currentChatUser: ChatUser?,
        allUnReadMsgs: List<Message>
    ) {
        showNotification(newContactIds)
        if (currentChatUser != null) {
            val currentUserMsgs = allUnReadMsgs.filter {
                it.chatUserId == currentChatUser.id
            }
            val otherUserMsgs = allUnReadMsgs.filter {
                it.chatUserId != currentChatUser.id
            }
            messageStatusUpdater.updateToDelivery(otherUserMsgs, *chatUsers.toTypedArray())
            messageStatusUpdater.updateToSeen(
                currentChatUser.id, currentChatUser.documentId!!, currentUserMsgs
            )
        } else {
            messageStatusUpdater.updateToDelivery(allUnReadMsgs, *chatUsers.toTypedArray())
        }
    }

    private fun showNotification(newContactIds: ArrayList<String>) {
        if (newContactIds.isEmpty()) {
            val lastMsgId = messagesList.maxOf { it.createdAt }
            val msg = messagesList.find { it.createdAt == lastMsgId }
            if (msg != null && msg.from != fromUser)
                FirebasePush.showNotification(context, dbRepository)
        } else {
            val distinctIds = newContactIds.distinct()
            for ((index, userId) in distinctIds.withIndex()) {
                if (userId == preference.getOnlineUser())
                    continue

                val unreadCount = messagesList.getUnreadCount(userId)
                chatUserUtil.queryNewUserProfile(
                    context,
                    userId,
                    null,
                    unreadCount,
                    showNotification = index == distinctIds.lastIndex
                )
            }
        }
    }
}