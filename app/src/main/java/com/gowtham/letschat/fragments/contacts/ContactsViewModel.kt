package com.gowtham.letschat.fragments.contacts

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.gowtham.letschat.core.QueryCompleteListener
import com.gowtham.letschat.db.DbRepository
import com.gowtham.letschat.db.data.ChatUser
import com.gowtham.letschat.models.UserProfile
import com.gowtham.letschat.utils.LoadState
import com.gowtham.letschat.utils.LogMessage
import com.gowtham.letschat.utils.UserUtils
import com.gowtham.letschat.utils.toast
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


@HiltViewModel
class ContactsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dbRepo: DbRepository) : ViewModel() {

    val queryState = MutableLiveData<LoadState>()

    val contactsCount = MutableLiveData("0 Contacts")

    init {
        LogMessage.v("ContactsViewModel init")
    }

    fun getContacts()=dbRepo.getAllChatUser()

    fun setContactCount(size: Int) {
        contactsCount.value="$size Contacts"
    }

    fun startQuery() {
        try {
            queryState.value=LoadState.OnLoading
            val success=UserUtils.updateContactsProfiles(onQueryCompleted)
            if (!success) {
                queryState.value = LoadState.OnFailure(java.lang.Exception("Failed to initiate query"))
                context.toast("No contacts found to query")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            queryState.value = LoadState.OnFailure(e)
        }
    }

    private val onQueryCompleted=object : QueryCompleteListener {
        override fun onQueryCompleted(queriedList: ArrayList<UserProfile>) {
            try {
                LogMessage.v("Query Completed ${queriedList.size}")
                CoroutineScope(Dispatchers.IO).launch {
                    val localContacts = UserUtils.fetchContacts(context)
                    val chatUsers = dbRepo.getChatUserList()
                    val finalList = ArrayList<ChatUser>()
                    for (doc in queriedList) {
                        val savedNumber = localContacts.firstOrNull {
                            UserUtils.numbersLikelySame(it.mobile, doc.mobile?.number)
                        }
                        if (savedNumber != null) {
                            val chatUser = UserUtils.getChatUser(doc, chatUsers, savedNumber.name)
                            finalList.add(chatUser)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        contactsCount.value = "${finalList.size} Contacts"
                        queryState.value = LoadState.OnSuccess(finalList)
                        withContext(Dispatchers.IO) {
                            dbRepo.insertMultipleUser(finalList)
                        }
                        context.toast("Contacts refreshed: ${finalList.size} found")
                        setDefaultValues()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                CoroutineScope(Dispatchers.Main).launch {
                    queryState.value = LoadState.OnFailure(e)
                }
            }
        }
    }

    private fun setDefaultValues() {
        UserUtils.totalRecursionCount=0
        UserUtils.resultCount=0
        UserUtils.queriedList.clear()
    }

    fun setUnReadCountZero(chatUser: ChatUser) {
        UserUtils.setUnReadCountZero(dbRepo,chatUser)
    }

}
