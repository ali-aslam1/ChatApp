package com.gowtham.letschat.core

import com.google.firebase.firestore.FirebaseFirestore
import com.gowtham.letschat.models.UserProfile
import com.gowtham.letschat.utils.UserUtils
import timber.log.Timber

interface QueryCompleteListener{
    fun onQueryCompleted(queriedList: ArrayList<UserProfile>)
}

class ContactsQuery(val list: ArrayList<String>,val position: Int,val listener: QueryCompleteListener){

    private val usersCollection = FirebaseFirestore.getInstance().collection("Users")

    fun makeQuery() {
        try {
            usersCollection.whereIn("mobile.number", list).get()
                .addOnSuccessListener { documents ->
                    val queriedProfiles = ArrayList<UserProfile>()
                    for (document in documents) {
                        val contact = document.toObject(UserProfile::class.java)
                        queriedProfiles.add(contact)
                    }
                    UserUtils.onContactsQuerySuccess(queriedProfiles, listener)
                }
                .addOnFailureListener { exception ->
                    Timber.w("Error getting contacts query at batch $position : ${exception.message}")
                    UserUtils.onContactsQueryFailure(listener)
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


}