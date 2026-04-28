package com.gowtham.letschat.core

import android.content.Context
import com.google.firebase.firestore.*
import com.gowtham.letschat.FirebasePush
import com.gowtham.letschat.db.DbRepository
import com.gowtham.letschat.db.data.Group
import com.gowtham.letschat.db.data.GroupMessage
import com.gowtham.letschat.di.GroupCollection
import com.gowtham.letschat.fragments.single_chat.toDataClass
import com.gowtham.letschat.utils.MPreference
import com.gowtham.letschat.utils.Utils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupChatHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preference: MPreference,
    private val userCollection: CollectionReference,
    @GroupCollection
    private val groupCollection: CollectionReference,
    private val dbRepository: DbRepository,
    private val groupMsgStatusUpdater: GroupMsgStatusUpdater
) {

    private var userId = preference.getUid()

    private val groupMsgListeners = HashMap<String, ListenerRegistration>()

    init {
        instance = this
    }

    companion object {
        private var myProfileListener: ListenerRegistration? = null
        private var instanceCreated = false
        private var instance: GroupChatHandler? = null

        fun removeListener() {
            instanceCreated = false
            myProfileListener?.remove()
            myProfileListener = null
            instance?.clearGroupMsgListeners()
        }
    }

    fun initHandler() {
        if (instanceCreated)
            return
        instanceCreated = true
        userId = preference.getUid()
        Timber.v("GroupChatHandler init")
        preference.clearCurrentGroup()
        addGroupsSnapShotListener()
    }

    private fun clearGroupMsgListeners() {
        groupMsgListeners.values.forEach { it.remove() }
        groupMsgListeners.clear()
    }

    private fun addGroupMsgListener(groupId: String) {
        if (groupMsgListeners.containsKey(groupId) || userId.isNullOrEmpty()) return

        val listener = groupCollection.document(groupId).collection("group_messages")
            .whereArrayContains("to", userId!!)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Timber.e("GroupChatHandler listener error for $groupId: ${error.localizedMessage}")
                    return@addSnapshotListener
                }

                if (snapshots == null || snapshots.isEmpty) return@addSnapshotListener

                val newMessages = mutableListOf<GroupMessage>()
                var hasNewMessage = false
                for (shot in snapshots.documentChanges) {
                    if (shot.type == DocumentChange.Type.ADDED ||
                        shot.type == DocumentChange.Type.MODIFIED
                    ) {
                        try {
                            val message = shot.document.data.toDataClass<GroupMessage>()
                            newMessages.add(message)
                            if (shot.type == DocumentChange.Type.ADDED && message.from != userId) {
                                hasNewMessage = true
                            }
                        } catch (e: Exception) {
                            Timber.e("Error parsing group message change: ${e.message}")
                        }
                    }
                }

                if (newMessages.isNotEmpty()) {
                    processIncomingMessages(newMessages, groupId, hasNewMessage)
                }
            }
        groupMsgListeners[groupId] = listener
    }

    private fun processIncomingMessages(messages: List<GroupMessage>, groupId: String, showNotification: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            dbRepository.insertMultipleGroupMessage(messages)
            val groupsWithMsgs = dbRepository.getGroupWithMessagesList()
            val currentOnlineGroupId = preference.getOnlineGroup()
            
            for (groupWithMsg in groupsWithMsgs) {
                if (groupWithMsg.group.id == groupId || groupWithMsg.group.id == currentOnlineGroupId) {
                    val unreadCount = groupWithMsg.messages.filter {
                        val myStatus = Utils.myMsgStatus(userId!!, it)
                        it.from != userId && it.groupId == groupWithMsg.group.id && myStatus < 3
                    }.size
                    groupWithMsg.group.unRead =
                        if (currentOnlineGroupId == groupWithMsg.group.id) 0
                        else unreadCount
                }
            }
            val groupsToUpdate = groupsWithMsgs.map { it.group }
            dbRepository.insertMultipleGroup(groupsToUpdate)
            
            // Notification and status update
            if (showNotification) {
                FirebasePush.showGroupNotification(context, dbRepository)
            }

            if (currentOnlineGroupId == groupId) {
                groupMsgStatusUpdater.updateToSeen(userId!!, messages, groupId)
            } else {
                groupMsgStatusUpdater.updateToDelivery(userId!!, messages, groupId)
            }
        }
    }

    private fun addGroupsSnapShotListener() {
        if (userId.isNullOrEmpty()) return

        myProfileListener =
            userCollection.document(userId!!).addSnapshotListener { snapshot, error ->
                if (error == null) {
                    val groups = snapshot?.get("groups")
                    val currentGroups =
                        if (groups == null) ArrayList<String>() else groups as ArrayList<String>
                    
                    // Remove listeners for groups we are no longer in
                    val removedGroups = groupMsgListeners.keys.minus(currentGroups.toSet())
                    for (id in removedGroups) {
                        groupMsgListeners[id]?.remove()
                        groupMsgListeners.remove(id)
                    }

                    for (groupId in currentGroups) {
                        addGroupMsgListener(groupId)
                    }

                    CoroutineScope(Dispatchers.IO).launch {
                        val alreadySavedGroup = dbRepository.getGroupList().map { it.id }
                        val newGroups = currentGroups.toSet().minus(alreadySavedGroup.toSet())
                        queryNewGroups(newGroups)
                    }
                }
            }
    }

    private fun queryNewGroups(newGroups: Set<String>) {
        Timber.v("New groups ${newGroups.size}")
        for (groupId in newGroups) {
            val groupQuery = GroupQuery(groupId, dbRepository, preference)
            groupQuery.getGroupData(groupCollection)
        }
    }

}
