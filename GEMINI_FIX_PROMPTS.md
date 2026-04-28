# LetsChat Upload Failures - Gemini AI Fix Prompts

## 🔴 EXACT CAUSES OF FAILURES

### **Problem 1: Profile Picture Upload Fails - "Permission Denied"**

**Root Cause Chain:**
1. `UserUtils.getStorageRef()` creates path: `Users/{uid}/profile_picture_{timestamp}.jpg`
2. UID source is INCONSISTENT:
   - Primary: `FirebaseAuth.getInstance().currentUser?.uid` (can be null during init)
   - Fallback: `MPreference.getUid()` (can be stale or different)
   - Final fallback: `"unknown"` string
3. Firebase Storage security rules check: `request.auth.uid == userId`
4. **FAILURE**: If UID becomes `"unknown"`, no user has uid="unknown" → Permission Denied
5. Profile Firestore update fails because `docuRef` points to `Users/unknown` (wrong user's document)

**Files with issues:**
- `UserUtils.kt` lines 147-152 (UID mismatch)
- `FMyProfileViewModel.kt` lines 38, 70-90 (null safety)

---

### **Problem 2: Media (Photo/Video) Sending Fails - "Failed Status"**

**Root Cause Chain:**
1. User selects image from gallery → URI stored as String
2. `UploadWorker.kt` tries to parse URI back: `Uri.parse(url)` or `Uri.fromFile(File(url))`
3. **ISSUE**: File might be deleted between selection and upload (system cache cleanup, user action)
4. `contentResolver.getType(uri)` returns null → Wrong file extension used
5. Upload completes but **message status NEVER updated in Firestore** (only in local DB)
6. User sees "sent" in UI (local DB), but Firestore still shows `status=0` "sending"
7. When app syncs, message reappears as "sending"
8. **NO TIMEOUT** on upload → hangs on slow networks

**Files with issues:**
- `UploadWorker.kt` lines 43-47 (file URI parsing), 85-99 (status update missing), 99-101 (MIME type)
- `FSingleChat.kt` lines 236-242 (file URI handling)
- `SingleChatViewModel.kt` lines 120-127 (no retry policy)

---

### **Problem 3: Voice Note Sending Fails - "Failed Status"**

**Root Cause Chain:**
1. Recording saved to `externalCacheDir` without null check
   - Can be null → **NullPointerException**
   - Can be cleared by system → File deleted after recording
2. Uses `MediaRecorder.AudioEncoder.AMR_NB` but saves as `.mp3` → **Audio codec mismatch**
   - MediaRecorder produces raw stream, not actual MP3
   - Receiver downloads file with `.mp3` extension but unplayable
3. `MediaRecorder.prepare()` exception caught but NOT propagated → Recording state inconsistent
4. File URI from cache directory: `Uri.fromFile(File(path))` → Path might be invalid/deleted
5. `contentResolver.getType()` returns null for cache files → Wrong extension
6. **Message status NEVER updated in Firestore** (same as media)
7. Permission checked once, but can be revoked before recording starts → Race condition

**Files with issues:**
- `FSingleChat.kt` lines 406-429 (MediaRecorder setup), 405-410 (permission handling)
- `UploadWorker.kt` lines 43-47 (URI parsing), 99-101 (MIME type), 85 (status update)

---

## 🎯 GEMINI PROMPTS TO FIX (Copy-Paste Into Android Studio)

---

## **PROMPT #1: Fix Profile Picture UID Mismatch**

**Use this when:**
- Cursor is in `UserUtils.kt` file
- Select lines 147-152 in `getStorageRef()` function

**Gemini Prompt:**
```
The profile picture upload is failing with "permission denied" error.
The issue is in getStorageRef() function. 

CURRENT CODE:
fun getStorageRef(context: Context): StorageReference {
    val preference = MPreference(context)
    val ref = Firebase.storage.getReference("Users")
    return ref.child(preference.getUid().toString())
}

PROBLEM:
- FirebaseAuth.getInstance().currentUser?.uid is null during some cases
- Falls back to preference.getUid() which might be stale or different
- Falls back to "unknown" string if UID is null
- Firebase Storage security rule checks: request.auth.uid == userId
- When userId is "unknown" or mismatched, permission denied error occurs

SOLUTION:
1. Get UID from FirebaseAuth.getInstance().currentUser?.uid (authoritative source)
2. If null, get from preference
3. After getting preference UID, ALWAYS sync it back to check consistency
4. Never fall back to "unknown" - throw exception instead
5. Add logging to detect UID mismatches

FIXED CODE should:
- Ensure UID always matches between Firebase Auth and local preference
- Validate UID is not empty/null before returning
- Log when falling back to preference UID
- Return proper error if UID cannot be determined

Can you refactor this function to fix the UID mismatch issue?
```

---

## **PROMPT #2: Fix Profile Firestore Update & Null Safety**

**Use this when:**
- Cursor is in `FMyProfileViewModel.kt` file
- Select lines 70-95 in `uploadProfileImage()` function

**Gemini Prompt:**
```
Profile picture upload is failing because Firestore document updates are not working.

CURRENT CODE:
fun uploadProfileImage(imagePath: Uri) {
    try {
        isUploading.value = true
        val storageRef = UserUtils.getStorageRef(context)
        val child = storageRef.child("profile_picture_${System.currentTimeMillis()}.jpg")
        uploadTask?.let {
            if (it.isInProgress) it.cancel()
        }
        uploadTask = child.putFile(imagePath)
        uploadTask?.addOnSuccessListener {
            child.downloadUrl.addOnCompleteListener { taskResult ->
                isUploading.value = false
                if (taskResult.isSuccessful) {
                    val newUrl = taskResult.result.toString()
                    imageUrl.value = newUrl
                    updateProfileData(userName.value ?: "", about.value ?: "", newUrl)
                } else {
                    val errorMsg = taskResult.exception?.message ?: "Failed to get download URL"
                    context.toast(errorMsg)
                }
            }
        }?.addOnFailureListener { e ->
            isUploading.value = false
            val errorMsg = e.message ?: "Upload failed"
            context.toast(errorMsg)
        }
    } catch (e: Exception) {
        isUploading.value = false
        e.printStackTrace()
    }
}

PROBLEMS:
1. uploadTask can be null, no safety check before calling addOnSuccessListener
2. downloadUrl retrieval has no timeout - can hang indefinitely
3. downloadUrl.addOnCompleteListener might fail silently if exception thrown
4. taskResult.result could be null even if isSuccessful=true
5. docuRef might point to wrong document if UID is mismatched
6. Missing error logging for debugging
7. Missing retry mechanism if upload fails

REQUIRED FIXES:
1. Add null safety for uploadTask
2. Add timeout (30-60 seconds) for downloadUrl retrieval
3. Wrap taskResult.result in null check
4. Add comprehensive error logging for each failure point
5. Verify docuRef is pointing to correct user document
6. Add retry option for failed uploads
7. Update Timber logs with detailed error information

WHAT I NEED YOU TO DO:
- Refactor uploadProfileImage() to add all null safety checks
- Add timeout handling for downloadUrl retrieval
- Add detailed Timber logging at each failure point
- Add verification that docuRef contains correct user UID
- Add try-catch blocks to prevent exceptions from being swallowed

Can you fix this function to add proper error handling and null safety?
```

---

## **PROMPT #3: Fix Media Upload File URI & Status Update**

**Use this when:**
- Cursor is in `UploadWorker.kt` file
- Select lines 40-105 in `doWork()` function

**Gemini Prompt:**
```
Media (image/video) sending is failing. Messages show "sent" in UI but stay "sending" in Firestore.

CURRENT CODE ISSUES:
1. File URI Parsing (lines 43-47):
   val uri = if (url.startsWith("content://") || url.startsWith("file://")) Uri.parse(url) 
             else Uri.fromFile(File(url))
   
   PROBLEM: File might be deleted before upload starts. Uri.fromFile() doesn't verify file exists.

2. MIME Type Detection (lines 95-101):
   val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(
       applicationContext.contentResolver.getType(uri)) ?: 
       if (message.type == "audio") "mp3" else "jpg"
   
   PROBLEM: getType(uri) returns null if file deleted. Falls back to generic extension.
   Result: MP4 video saved as .jpg, M4A audio saved as .mp3 → Unplayable on receiver.

3. Message Status Not Updated (lines 85, 92, 99):
   message.status = 4
   dbRepository.insertMessage(message)  // Only updates LOCAL database!
   
   PROBLEM: Firestore still shows status=0 "sending". Next app restart, message reappears as "sending".

4. No Timeout on Upload (line 65):
   val task = child.putFile(uri)  // No timeout configured
   countDownLatch.await()  // Can block forever on slow network
   
   PROBLEM: Hangs indefinitely if network disconnects mid-upload.

REQUIRED FIXES:
1. Validate file exists BEFORE creating putFile() task
2. Get actual file extension from file system, not MIME type
3. For MIME type fallback, use actual message.type field
4. ADD CRITICAL: Update message status in Firestore when upload fails
5. Add timeout to countDownLatch.await() (30-60 seconds)
6. Add proper error logging with exception details
7. Handle file not found errors explicitly

FIRESTORE STATUS UPDATE MISSING:
When upload fails, should also do:
```kotlin
val failureUpdate = mapOf("status" to 4)
msgCollection.document(docId)
    .collection("messages")
    .document(message.createdAt.toString())
    .update(failureUpdate)
    .addOnSuccessListener {
        message.status = 4
        dbRepository.insertMessage(message)
    }
```

WHAT I NEED YOU TO DO:
1. Add file existence check before putFile()
2. Fix MIME type detection to use actual file properties
3. Add Firestore status update on upload failure
4. Add timeout to countDownLatch.await()
5. Add detailed Timber logging for all failure paths
6. Handle specific error cases (file not found, network timeout, permission denied)

Can you refactor this function to fix file URI handling, MIME type detection, Firestore status updates, and timeout issues?
```

---

## **PROMPT #4: Fix Voice Note Recording & File Handling**

**Use this when:**
- Cursor is in `FSingleChat.kt` file
- Select lines 406-429 in `startRecording()` function

**Gemini Prompt:**
```
Voice note recording is failing. Audio files are unplayable or disappear after sending.

CURRENT CODE ISSUES:
1. External Cache Directory Not Null-Checked (line 412):
   lastAudioFile = "${requireActivity().externalCacheDir?.absolutePath}/audiorecord${System.currentTimeMillis()}.mp3"
   
   PROBLEM: externalCacheDir can be null → NullPointerException
   PROBLEM: External cache can be cleared by system anytime
   RESULT: File disappears after upload or upload fails

2. Wrong Audio Codec (lines 415-416):
   setOutputFormat(MediaRecorder.OutputFormat.DEFAULT)
   setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
   
   PROBLEM: Saves as .mp3 extension but uses AMR_NB codec
   PROBLEM: MediaRecorder.OutputFormat.DEFAULT doesn't produce MP3
   RESULT: File extension is .mp3 but format is raw audio → Unplayable

3. Exception Not Propagated (lines 422-424):
   try {
       prepare()
   } catch (e: IOException) {
       println("ChatFragment.startRecording${e.message}")  // Only prints!
   }
   start()
   
   PROBLEM: If prepare() fails, exception caught but ignored
   PROBLEM: start() called on unprepared recorder → Crashes
   PROBLEM: Recording state shows isRecording=true but recorder failed
   RESULT: Inconsistent state, no error shown to user

4. Recording Files Never Deleted:
   lastAudioFile stored but never cleaned up after upload
   External cache accumulates old recordings
   User storage fills up

5. Permission Not Re-Checked (lines 207-210):
   if(Utils.checkPermission(this, Manifest.permission.RECORD_AUDIO, reqCode = REQ_AUDIO_PERMISSION))
       startRecording()
   
   PROBLEM: Permission checked once, but can be revoked in Settings
   PROBLEM: Race condition between check and actual recording
   RESULT: MediaRecorder.prepare() throws exception, no fallback

REQUIRED FIXES:
1. Check if externalCacheDir is available, use app cache directory as fallback
2. Fix audio codec to produce actual MP3 or change extension to match codec
3. Properly handle MediaRecorder.prepare() exceptions - don't swallow errors
4. Set isRecording=true only AFTER recorder.start() succeeds
5. Re-check RECORD_AUDIO permission before start()
6. Delete recording file after successful upload
7. Set reasonable file size limits to prevent storage exhaustion
8. Add proper error logging with exception details

CORRECT APPROACH:
- Use app cache directory (no permission needed): context.cacheDir
- Or use external cache only if available and writable
- Use AudioEncoder that matches file extension
- Add try-catch with proper error propagation
- Only mark isRecording=true after verified success
- Re-validate permission right before starting recorder

WHAT I NEED YOU TO DO:
1. Fix external cache directory handling with null check and fallback
2. Fix audio codec to match file format
3. Fix MediaRecorder.prepare() exception handling
4. Only set isRecording=true after successful start
5. Add permission re-check right before recording
6. Add file cleanup after successful upload
7. Add comprehensive error messages for users

Can you refactor startRecording() and stopRecording() to fix all these issues?
```

---

## **PROMPT #5: Fix Voice Note Upload & Firestore Status**

**Use this when:**
- Cursor is in `UploadWorker.kt` file  
- Select lines 40-110 for complete overview
- Focus on lines 43-47 (file URI) and 85-99 (status update)

**Gemini Prompt:**
```
Voice notes are being uploaded but show as "sending" forever in Firestore.

ISSUES (Same as Media Uploads + Audio-specific):
1. File URI from cache directory might be invalid (file deleted by system)
2. MIME type detection fails for cache files
3. Message.audioMessage?.uri might not be set (null check fails)
4. Message status never updated in Firestore (stays as "sending")
5. No timeout on countDownLatch.await() → hangs indefinitely

FIRESTORE STATUS UPDATE IS MISSING:
Currently when upload fails:
- Local DB updated with status=4
- BUT Firestore document STILL shows status=0 "sending"

WHAT I NEED YOU TO DO:
1. Fix audioMessage null check - use safe navigation AND verify audioMessage initialized
2. Add Firestore status update (same as media fix)
3. Add file existence validation before putFile()
4. Fix URI parsing for cache directory files
5. Add timeout to countDownLatch.await()
6. Add error logging for debugging

CRITICAL FIX NEEDED:
When audio upload fails, must also update Firestore:
```kotlin
msgCollection.document(docId)
    .collection("messages")
    .document(message.createdAt.toString())
    .update(mapOf("status" to 4))
    .addOnFailureListener { e ->
        Timber.e(e, "Failed to update audio message status in Firestore")
    }
```

Can you apply the same Firestore status update fix from media uploads to voice notes, plus handle the audio-specific file URI and audioMessage null issues?
```

---

## **PROMPT #6: Fix WorkManager Retry Policy**

**Use this when:**
- Cursor is in `SingleChatViewModel.kt` file
- Select lines 115-127 in `uploadToCloud()` function

**Gemini Prompt:**
```
Uploads fail and don't retry automatically. Users send message, it shows failed, stays failed.

CURRENT CODE (lines 115-127):
val uploadWorkRequest: WorkRequest =
    OneTimeWorkRequestBuilder<UploadWorker>()
        .setInputData(data)
        .build()

PROBLEMS:
1. No retry policy configured
2. No network constraints
3. Failed uploads don't automatically retry
4. No timeout configuration
5. User has no way to manually retry

WHAT I NEED YOU TO DO:
Add to uploadToCloud() function:
1. Configure ExponentialBackoff retry policy (exponential: 30s, 60s, 120s...)
2. Add constraints to only retry when network available
3. Set initial delay before first retry
4. Add maximum number of retries (3-5)
5. Add WorkManager failure callback to notify user
6. (Optional) Add manual retry button for failed messages

REQUIRED CONFIGURATION:
- BackoffPolicy.EXPONENTIAL with MIN_BACKOFF_MILLIS
- NetworkType.CONNECTED constraint
- Max retry attempts
- Proper exception handling

Can you update uploadToCloud() to add retry policy with exponential backoff and network constraints?
```

---

## **PROMPT #7: Fix CountDownLatch Timeout**

**Use this when:**
- Cursor is in `UploadWorker.kt` file
- Select lines 65-105 in `doWork()` function

**Gemini Prompt:**
```
Upload worker hangs indefinitely on slow networks. WorkManager kills worker after timeout.

CURRENT CODE (line 103):
val countDownLatch = CountDownLatch(1)
val result = arrayOf(Result.failure())
task.addOnSuccessListener { /* ... countDownLatch.countDown() */ }
// ... other callbacks ...
countDownLatch.await()  // NO TIMEOUT!

PROBLEM:
- countDownLatch.await() blocks forever if network disconnects
- Callback might never execute (network issue, device sleep, etc.)
- WorkManager's internal timeout kills worker but user sees stuck upload
- No graceful recovery mechanism

WHAT I NEED YOU TO DO:
1. Add timeout to countDownLatch.await() (30-60 seconds)
2. Handle timeout by returning Result.retry()
3. Log timeout event with error details
4. Gracefully cancel the upload task on timeout
5. Notify user of timeout through log

IMPLEMENTATION:
```kotlin
val timeoutReached = !countDownLatch.await(60, TimeUnit.SECONDS)
if (timeoutReached) {
    Timber.w("Upload timeout after 60 seconds, marking for retry")
    task.cancel(true)
    return Result.retry()
}
return result[0]
```

Can you add timeout handling to countDownLatch.await() with proper error logging and task cancellation?
```

---

## **PROMPT #8: Fix GroupMessage Status Array**

**Use this when:**
- Cursor is in `GroupUploadWorker.kt` file
- Select lines 50-60 in `doWork()` function and lines 75-80 in failure handlers

**Gemini Prompt:**
```
Group media uploads crash with IndexOutOfBoundsException when trying to update message status.

CURRENT CODE (lines 54, 75, 80):
message.status[0] = 4
message.status[0] = 4

PROBLEM:
GroupMessage.status is ArrayList<Int> - can be empty!
If status array is empty, status[0] throws IndexOutOfBoundsException
Exception is silently caught by WorkManager, task marked as failed
Message status never updated

WHAT I NEED YOU TO DO:
1. Check if status array is empty before accessing
2. If empty, initialize it: status = arrayListOf(4)
3. Add logging when initializing
4. Same fix needed in all three places where status is updated (success, failure, download URL failure)
5. (Bonus) Add parameter validation in GroupMessage constructor

IMPLEMENTATION PATTERN:
```kotlin
if (message.status.isNotEmpty()) {
    message.status[0] = 4
} else {
    Timber.w("GroupMessage status array was empty, initializing")
    message.status = arrayListOf(4)
}
```

Can you fix status array access in GroupUploadWorker to check if array is empty before indexing, with proper initialization and logging?
```

---

## **PROMPT #9: Add Firestore Status Update to GroupUploadWorker**

**Use this when:**
- Cursor is in `GroupUploadWorker.kt` file
- Select lines 60-80 (failure handling sections)

**Gemini Prompt:**
```
Group messages stay "sending" in Firestore even after upload fails locally.

CURRENT CODE (lines 75-80):
}.addOnFailureListener { exception ->
    Timber.e(exception, "Failed to get download URL for group message")
    result[0]= Result.failure()
    message.status[0]=4
    dbRepository.insertMessage(message)  // Only local DB!

PROBLEM:
- Firestore document still shows status[0]=0 "sending"
- Local DB shows status[0]=4 "failed"
- Out of sync: UI shows failed, but Firestore sync shows sending
- Group members don't see failure status

WHAT I NEED YOU TO DO:
1. Add Firestore status update when upload fails
2. Update status in messages subcollection to 4 (failed)
3. Handle Firestore update errors properly
4. Log both local DB and Firestore updates

IMPLEMENTATION NEEDED:
Before calling dbRepository.insertMessage(), add:
```kotlin
val group = Json.decodeFromString<Group>(params.inputData.getString(Constants.GROUP_DATA)!!)
groupCollection.document(group.id)
    .collection("messages")
    .document(message.createdAt.toString())
    .update(mapOf("status" to arrayListOf(4)))
    .addOnFailureListener { e ->
        Timber.e(e, "Failed to update group message status in Firestore")
    }
```

Then update local DB:
```kotlin
message.status = arrayListOf(4)
dbRepository.insertMessage(message)
```

Can you add Firestore status update to GroupUploadWorker failure handlers, before updating local database?
```

---

## **PROMPT #10: Fix Message Status "Sent" Transition**

**Use this when:**
- Cursor is in `MessageStatusUpdater.kt` file
- OR `MessageSender.kt` file (around line 67)

**Gemini Prompt:**
```
Messages show as "sending" forever even after successfully uploaded and sent.

CURRENT CODE ISSUE:
MessageStatusUpdater.kt has methods:
- updateToDelivery() - status 1→2
- updateToSeen() - status →3

BUT MISSING:
- updateToSent() - status 0→1 (MISSING!)

MessageSender.kt line 67:
message.status = 1  // Set locally to "sent"

But then uses SetOptions.merge() which preserves old fields:
docuRef.set(message, SetOptions.merge())  // Might not update status field

PROBLEM:
- Message sent to Firestore with status=1
- But merge() preserves old status=0 if document already exists
- Result: UI shows "sending", Firestore shows "sending"

WHAT I NEED YOU TO DO:
1. Create updateToSent() method in MessageStatusUpdater
2. Call this method after sendMessage() succeeds
3. Update message status in Firestore from 0→1
4. Handle cases where message document already exists
5. Use .set() instead of merge() for status field, OR use explicit .update()

IMPLEMENTATION:
```kotlin
// In MessageStatusUpdater.kt:
fun updateToSent(messageList: List<Message>, docId: String) {
    messageList.forEach { message ->
        if (message.status < 1) {
            msgCollection.document(docId)
                .collection("messages")
                .document(message.createdAt.toString())
                .update(mapOf("status" to 1))
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to update message status to sent")
                }
        }
    }
}

// Then call after sendMessage succeeds:
messageStatusUpdater.updateToSent(listOf(message), docId)
```

Can you add updateToSent() method to MessageStatusUpdater and fix the status transition from 0→1 in Firestore?
```

---

## 📋 IMPLEMENTATION PRIORITY

**Do FIRST (P0 - Blocking):**
1. Prompt #1 - Fix UID Mismatch
2. Prompt #2 - Fix Profile Firestore Update
3. Prompt #3 - Fix Media Upload File URI & Status
4. Prompt #4 - Fix Voice Note Recording
5. Prompt #7 - Fix CountDownLatch Timeout

**Then (P1 - High):**
6. Prompt #5 - Fix Voice Note Upload Status
7. Prompt #6 - Fix WorkManager Retry
8. Prompt #8 - Fix GroupMessage Status Array
9. Prompt #9 - Add Firestore Status to GroupUploadWorker

**Finally (P2 - Medium):**
10. Prompt #10 - Fix Message Status Transitions

---

## 🧪 TESTING AFTER FIXES

After implementing fixes, test these scenarios:

1. **Profile Picture Upload:**
   - [ ] Upload on WiFi → should succeed and show in Firestore
   - [ ] Upload on 2G/3G → should timeout gracefully and allow retry
   - [ ] Upload after deleting app cache → should work with fresh UID sync

2. **Media Sending:**
   - [ ] Send image → verify status shows "sent" in Firestore
   - [ ] Send with slow network → should show upload progress, not hang
   - [ ] Delete file after selection → should fail gracefully with error
   - [ ] Check receiver can view/download image correctly

3. **Voice Notes:**
   - [ ] Record audio → should save to proper location
   - [ ] Record with no external storage → should use fallback and succeed
   - [ ] Send voice note → verify status shows "sent" in Firestore
   - [ ] Check receiver can play audio correctly

