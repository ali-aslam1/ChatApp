package com.gowtham.letschat.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import com.fcmsender.FCMSender
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.ktx.storage
import com.gowtham.letschat.MApplication
import com.gowtham.letschat.core.*
import com.gowtham.letschat.db.ChatUserDatabase
import com.gowtham.letschat.db.DbRepository
import com.gowtham.letschat.db.daos.GroupDao
import com.gowtham.letschat.db.daos.GroupMessageDao
import com.gowtham.letschat.db.data.*
import com.gowtham.letschat.fragments.group_chat_home.AdGroupChatHome
import com.gowtham.letschat.fragments.single_chat_home.AdSingleChatHome
import com.gowtham.letschat.models.Contact
import com.gowtham.letschat.models.ModelDeviceDetails
import com.gowtham.letschat.models.UserProfile
import com.gowtham.letschat.models.UserStatus
import com.gowtham.letschat.ui.activities.ActSplash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject
import timber.log.Timber
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.system.measureNanoTime

object UserUtils {

    var queriedList=ArrayList<UserProfile>()

    var resultCount=0

    const val NOTIFICATION_ID=22

    var totalRecursionCount=0

    private fun sanitizeNumber(number: String?): String {
        return number?.replace(Regex("[^0-9]"), "") ?: ""
    }

    private fun dropLeadingZeros(number: String): String {
        return number.trimStart('0')
    }

    private fun getNumberVariants(number: String?): Set<String> {
        val variants = linkedSetOf<String>()
        val sanitized = sanitizeNumber(number)
        if (sanitized.length < 7) return variants
        variants.add(sanitized)
        val withoutLeadingZero = dropLeadingZeros(sanitized)
        if (withoutLeadingZero.length >= 7) {
            variants.add(withoutLeadingZero)
        }
        if (sanitized.length > 10) {
            variants.add(sanitized.takeLast(10))
        }
        if (withoutLeadingZero.length > 10) {
            variants.add(withoutLeadingZero.takeLast(10))
        }
        return variants
    }

    fun numbersLikelySame(firstNumber: String?, secondNumber: String?): Boolean {
        if (firstNumber.isNullOrBlank() || secondNumber.isNullOrBlank()) return false
        val firstVariants = getNumberVariants(firstNumber)
        val secondVariants = getNumberVariants(secondNumber)
        if (firstVariants.isEmpty() || secondVariants.isEmpty()) return false
        if (firstVariants.any { secondVariants.contains(it) }) return true
        return firstVariants.any { first ->
            secondVariants.any { second ->
                first.endsWith(second) || second.endsWith(first)
            }
        }
    }

    fun updatePushToken(context: Context,userCollection: CollectionReference, isSave: Boolean) {
        try {
            if (Utils.isNetConnected(context)) {
                FirebaseInstallations.getInstance().getToken(false).addOnSuccessListener { result->
                    MPreference(context).updatePushToken(result.token)
                    if (isSave)
                        updateDeviceDetails(context,userCollection)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateDeviceDetails(context: Context,userCollection: CollectionReference) {
        val preference = MPreference(context)
        val token = preference.getPushToken()
        Timber.v("AAA ${preference.getUid()}")
        Timber.v("BB ${preference.getUserProfile()?.uId}")
        if (token.isNullOrEmpty())
            updatePushToken(context,userCollection, true)
        else if (Utils.isNetConnected(context)) {
            val profile = preference.getUserProfile()?.apply {
                this.token=token
                this.deviceDetails=
                    Json.decodeFromString<ModelDeviceDetails>(getDeviceInfo(context).toString())
            }
            val updateData = hashMapOf(
                "token" to token,
                "updatedAt" to System.currentTimeMillis(),
                "device_details" to Json.decodeFromString<ModelDeviceDetails>(getDeviceInfo(context).toString()),
            )
            userCollection.document(preference.getUid()!!).update(updateData).addOnSuccessListener {
                preference.saveProfile(profile!!)
                LogMessage.v("Token Updated $token##")
            }
        }
    }

    fun getStorageRef(context: Context): StorageReference {
        val preference = MPreference(context)
        val ref = Firebase.storage.getReference("Users")
        return ref.child(preference.getUid().toString())
    }

    fun getDocumentRef(context: Context): DocumentReference {
        val preference = MPreference(context)
        val db = FirebaseFirestore.getInstance()
        return db.collection("Users").document(preference.getUid()!!)
    }

    fun getMessageSubCollectionRef(): Query {
        val db = FirebaseFirestore.getInstance()
        return db.collectionGroup("messages")
    }

    fun getGroupMsgSubCollectionRef(): Query {
        val db = FirebaseFirestore.getInstance()
        return db.collectionGroup("group_messages")
    }

    fun fetchContacts(context: Context): List<Contact> {
        val preference=MPreference(context)
        val contacts=ArrayList<Contact>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val resolver = context.contentResolver
        val cursor = resolver.query(uri, projection, null, null, null)
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val number = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))

                // Keep only digits for comparison
                val sanitizedNumber = sanitizeNumber(number)

                if (sanitizedNumber.length < 7) continue

                if (preference.getMobile() != null &&
                    numbersLikelySame(sanitizedNumber, preference.getMobile()!!.number)
                )
                    continue

                contacts.add(Contact(name, sanitizedNumber))
            }
        }
        val hashMap = getSanitizedContactMap(contacts)
        val finalList = ArrayList<Contact>()
        for (number in hashMap.keys){
            finalList.add(Contact(hashMap[number].toString(), number))
        }
        return finalList.sortedWith(compareBy { it.name })
    }

    private fun getSanitizedContactMap(contacts: ArrayList<Contact>): HashMap<String, String> {
        val hashMap: HashMap<String, String> = HashMap()
        contacts.forEach { contact ->
            hashMap[contact.mobile] = contact.name
        }
        return hashMap
    }

    fun getDeviceInfo(context: Context): JSONObject {
        try {
            val deviceInfo = JSONObject()
            deviceInfo.put("device_id", getDeviceId(context))
            deviceInfo.put("device_model", Build.MODEL)
            deviceInfo.put("device_brand", Build.BOARD)
            deviceInfo.put("device_country", Locale.getDefault())
            deviceInfo.put("device_os_v", Build.VERSION.RELEASE)
            deviceInfo.put("app_version", getVersionName(context))
            deviceInfo.put("package_name", context.packageName)
            deviceInfo.put("device_type", "android")
            return deviceInfo
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return JSONObject()
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String? {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }

    private fun getVersionName(context: Context): String? {
        try {
            val packageName = context.packageName
            val pInfo = context.packageManager.getPackageInfo(packageName, 0)
            return pInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        return "1.0"
    }

    fun logOut(context: Activity, preference: MPreference,db: ChatUserDatabase) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                db.clearAllTables()
            }
            EventBus.getDefault().post(UserStatus("offline"))
            ChatHandler.removeListeners()
            GroupChatHandler.removeListener()
            ChatUserProfileListener.removeListener()
            AdSingleChatHome.allChatList= emptyList<ChatUserWithMessages>().toMutableList()
            AdGroupChatHome.allList= emptyList<GroupWithMessages>().toMutableList()
            FirebaseAuth.getInstance().signOut()
            preference.clearAll()
            Utils.startNewActivity(context, ActSplash::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendPush(context: Context, type: String,body: String, token: String,to: String) {
        try {
            val data=JSONObject()
            val pushData=JSONObject()
            data.put("type", type)
            data.put("message_body",body)
            data.put("to",to)
            pushData.put("data",data)
            val push = FCMSender.Builder()
                .serverKey(Constants.FCM_SERVER_KEY)
                .setData(pushData)
                .toTokenOrTopic(token)
                .responseListener(object : FCMSender.ResponseListener {
                    override fun onFailure(errorCode: Int,message: String) {
                        LogMessage.v("notification sent Failed to $token")
                    }

                    override fun onSuccess(response: String) {
                        LogMessage.v("notification sent Successfully to $token")
                    }
                }).build()
            push.sendPush(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setUnReadCountZero(repo: DbRepository, chatUser: ChatUser) {
        try {
            val time= measureNanoTime {
                chatUser.unRead=0
                repo.insertUser(chatUser)
            }
            Timber.v("Taken time $time")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    fun getChatUserId(fromUser: String, message: Message)= if (message.from != fromUser) message.from
    else message.to

    fun sendTypingStatus(database: FirebaseDatabase, isTyping: Boolean, vararg users: String) {
        try {
            val typingRef = database.getReference("/Users/${users[0]}/typing_status")
            val chatUserRef = database.getReference("/Users/${users[0]}/chatuser")
            typingRef.setValue(if (isTyping) "typing" else "not_typing")
            chatUserRef.setValue(users[1])
            typingRef.onDisconnect().setValue("not_typing")
            chatUserRef.onDisconnect().setValue("")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateContactsProfiles(listener :QueryCompleteListener?): Boolean {
        Timber.v("Query Called")
        setDefaultValues()
        val allContacts = fetchContacts(MApplication.appContext).toMutableList()
        val listOfMobiles = LinkedHashSet<String>()
        allContacts.forEach {
            listOfMobiles.addAll(getNumberVariants(it.mobile))
        }
        if(listOfMobiles.isEmpty())
            return false
        val chunkedMobileList = listOfMobiles.toList().chunked(10)
        totalRecursionCount = chunkedMobileList.size
        chunkedMobileList.forEachIndexed { index, mobiles ->
            ContactsQuery(ArrayList(mobiles), index + 1, listener ?: onQueryCompleted).makeQuery()
        }
        LogMessage.v("Queried times $totalRecursionCount")
        return true
    }

    fun onContactsQuerySuccess(docs: List<UserProfile>, listener: QueryCompleteListener) {
        synchronized(this) {
            docs.forEach { doc ->
                if (queriedList.none { it.uId == doc.uId }) {
                    queriedList.add(doc)
                }
            }
            resultCount += 1
            if (resultCount == totalRecursionCount) {
                listener.onQueryCompleted(ArrayList(queriedList))
            }
        }
    }

    fun onContactsQueryFailure(listener: QueryCompleteListener) {
        synchronized(this) {
            resultCount += 1
            if (resultCount == totalRecursionCount) {
                listener.onQueryCompleted(ArrayList(queriedList))
            }
        }
    }

    fun getChatUser(
        doc: UserProfile,
        chatUsers: List<ChatUser>,
        savedName: String): ChatUser {
        var existData: ChatUser? = null
        if (chatUsers.isNotEmpty()) {
            val contact = chatUsers.firstOrNull { it.id == doc.uId }
            contact?.let {
                existData=it
            }
        }
        return existData?.apply {
            user = doc
            localName = savedName
            locallySaved=true
        } ?: ChatUser(doc.uId.toString(), savedName, doc,locallySaved = true)
    }


    private val onQueryCompleted=object : QueryCompleteListener {
        override fun onQueryCompleted(queriedList: ArrayList<UserProfile>) {
            try {
                Timber.v("onQueryCompleted ${queriedList.size}")
                val localContacts= fetchContacts(MApplication.appContext)
                val finalList = ArrayList<ChatUser>()
                //set localsaved name to queried users
                CoroutineScope(Dispatchers.IO).launch {
                    val chatUsers=MApplication.userDaoo.getChatUserList()
                    withContext(Dispatchers.Main){
                        for(doc in queriedList){
                            val docMobile = doc.mobile?.number

                            val savedNumber = localContacts.firstOrNull { 
                                numbersLikelySame(it.mobile, docMobile)
                            }

                            if(savedNumber!=null){
                                val chatUser=getChatUser(doc,chatUsers,savedNumber.name)
                                finalList.add(chatUser)
                            }
                        }
                        setDefaultValues()
                        withContext(Dispatchers.IO){
                            MApplication.userDaoo.insertMultipleUser(finalList)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setDefaultValues() {
        totalRecursionCount =0
        resultCount =0
        queriedList.clear()
    }

    fun setUnReadCountGroup(groupDao: GroupDao, group: Group) {
        CoroutineScope(Dispatchers.IO).launch {
            group.unRead=0
            groupDao.insertGroup(group)
        }
    }

    fun insertGroupMsg(groupMsgDao: GroupMessageDao, message: GroupMessage) {
        CoroutineScope(Dispatchers.IO).launch {
            groupMsgDao.insertMessage(message)
        }
    }

    fun insertMutlipleGroupMsg(groupMsgDao: GroupMessageDao, messages: List<GroupMessage>) {
        CoroutineScope(Dispatchers.IO).launch {
            groupMsgDao.insertMultipleMessage(messages)
        }
    }
}
