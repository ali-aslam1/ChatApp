package com.gowtham.letschat.fragments.group_chat

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gowtham.letschat.R
import com.gowtham.letschat.databinding.*
import com.gowtham.letschat.db.data.ChatUser
import com.gowtham.letschat.db.data.GroupMessage
import com.gowtham.letschat.utils.*
import com.gowtham.letschat.utils.Events.EventAudioMsg
import org.greenrobot.eventbus.EventBus
import timber.log.Timber
import java.io.IOException
import java.util.ArrayList

class AdGroupChat(private val context: Context, private val msgClickListener: ItemClickListener) :
    ListAdapter<GroupMessage, RecyclerView.ViewHolder>(DiffCallbackMessages()) {

    private val preference = MPreference(context)

    companion object {
        private const val TYPE_TXT_SENT = 0
        private const val TYPE_TXT_RECEIVED = 1
        private const val TYPE_IMG_SENT = 2
        private const val TYPE_IMG_RECEIVE = 3
        private const val TYPE_STICKER_SENT = 4
        private const val TYPE_STICKER_RECEIVE = 5
        private const val TYPE_AUDIO_SENT = 6
        private const val TYPE_AUDIO_RECEIVE = 7
        
        lateinit var messageList: MutableList<GroupMessage>
        lateinit var chatUserList: MutableList<ChatUser>
        
        private var lastPlayedSentHolder: RowGroupAudioSentBinding? = null
        private var lastPlayedReceivedHolder: RowGroupAudioReceiveBinding? = null
        private var lastPlayedAudioId: Long = -1
        private var player = MediaPlayer()
        private val handler = Handler(Looper.getMainLooper())

        private val updateSeekBar = object : Runnable {
            override fun run() {
                try {
                    if (player.isPlaying) {
                        val currentPos = player.currentPosition
                        lastPlayedSentHolder?.let {
                            it.seekBar.progress = currentPos
                            it.txtDuration.text = Utils.formatDuration(currentPos.toLong())
                        }
                        lastPlayedReceivedHolder?.let {
                            it.seekBar.progress = currentPos
                            it.txtDuration.text = Utils.formatDuration(currentPos.toLong())
                        }
                        handler.postDelayed(this, 100)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        fun stopPlaying() {
            handler.removeCallbacks(updateSeekBar)
            if (player.isPlaying) {
                player.stop()
            }
            player.reset()
            
            lastPlayedSentHolder?.let {
                it.imgPlay.setImageResource(R.drawable.ic_action_play)
                it.seekBar.progress = 0
                it.txtDuration.text = Utils.formatDuration(it.message?.audioMessage?.duration?.toLong()?.times(1000) ?: 0L)
            }
            lastPlayedReceivedHolder?.let {
                it.imgPlay.setImageResource(R.drawable.ic_action_play)
                it.seekBar.progress = 0
                it.txtDuration.text = Utils.formatDuration(it.message?.audioMessage?.duration?.toLong()?.times(1000) ?: 0L)
            }
            
            EventBus.getDefault().post(EventAudioMsg(false))
        }

        fun isPlaying() = player.isPlaying
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TXT_SENT -> TxtSentMsgHolder(RowGroupTxtSentBinding.inflate(layoutInflater, parent, false))
            TYPE_TXT_RECEIVED -> TxtReceivedMsgHolder(RowGrpTxtReceiveBinding.inflate(layoutInflater, parent, false))
            TYPE_IMG_SENT -> ImgSentMsgHolder(RowGroupImageSentBinding.inflate(layoutInflater, parent, false))
            TYPE_IMG_RECEIVE -> ImgReceivedMsgHolder(RowGroupImageReceiveBinding.inflate(layoutInflater, parent, false))
            TYPE_STICKER_SENT -> StickerSentMsgHolder(RowGroupStickerSentBinding.inflate(layoutInflater, parent, false))
            TYPE_STICKER_RECEIVE -> StickerReceivedMsgHolder(RowGroupStickerReceiveBinding.inflate(layoutInflater, parent, false))
            TYPE_AUDIO_SENT -> AudioSentVHolder(RowGroupAudioSentBinding.inflate(layoutInflater, parent, false))
            else -> AudioReceiveVHolder(RowGroupAudioReceiveBinding.inflate(layoutInflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is TxtSentMsgHolder -> holder.bind(item)
            is TxtReceivedMsgHolder -> holder.bind(item)
            is ImgSentMsgHolder -> holder.bind(item, msgClickListener)
            is ImgReceivedMsgHolder -> holder.bind(item, msgClickListener)
            is StickerSentMsgHolder -> holder.bind(item)
            is StickerReceivedMsgHolder -> holder.bind(item)
            is AudioSentVHolder -> holder.bind(context, item)
            is AudioReceiveVHolder -> holder.bind(context, item)
        }
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        val fromMe = message.from == preference.getUid()
        return when (message.type) {
            "text" -> if (fromMe) TYPE_TXT_SENT else TYPE_TXT_RECEIVED
            "image" -> {
                val isMedia = message.imageMessage?.imageType == "image"
                if (fromMe) (if (isMedia) TYPE_IMG_SENT else TYPE_STICKER_SENT)
                else (if (isMedia) TYPE_IMG_RECEIVE else TYPE_STICKER_RECEIVE)
            }
            "audio" -> if (fromMe) TYPE_AUDIO_SENT else TYPE_AUDIO_RECEIVE
            else -> super.getItemViewType(position)
        }
    }

    class TxtSentMsgHolder(val binding: RowGroupTxtSentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GroupMessage) {
            binding.message = item
            binding.executePendingBindings()
        }
    }

    class TxtReceivedMsgHolder(val binding: RowGrpTxtReceiveBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GroupMessage) {
            binding.message = item
            binding.chatUsers = chatUserList.toTypedArray()
            binding.executePendingBindings()
        }
    }

    class ImgSentMsgHolder(val binding: RowGroupImageSentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GroupMessage, msgClickListener: ItemClickListener) {
            binding.message = item
            binding.imageMsg.setOnClickListener { msgClickListener.onItemClicked(it, bindingAdapterPosition) }
            binding.executePendingBindings()
        }
    }

    class ImgReceivedMsgHolder(val binding: RowGroupImageReceiveBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GroupMessage, msgClickListener: ItemClickListener) {
            binding.message = item
            binding.chatUsers = chatUserList.toTypedArray()
            binding.imageMsg.setOnClickListener { msgClickListener.onItemClicked(it, bindingAdapterPosition) }
            binding.executePendingBindings()
        }
    }

    class StickerSentMsgHolder(val binding: RowGroupStickerSentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GroupMessage) {
            binding.message = item
            binding.executePendingBindings()
        }
    }

    class StickerReceivedMsgHolder(val binding: RowGroupStickerReceiveBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GroupMessage) {
            binding.message = item
            binding.chatUsers = chatUserList.toTypedArray()
            binding.executePendingBindings()
        }
    }

    class AudioReceiveVHolder(val binding: RowGroupAudioReceiveBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(context: Context, item: GroupMessage) {
            binding.message = item
            val durationMs = (item.audioMessage?.duration ?: 0) * 1000
            binding.seekBar.max = durationMs
            binding.seekBar.progress = 0
            binding.txtDuration.text = Utils.formatDuration(durationMs.toLong())
            
            binding.imgPlay.setOnClickListener {
                if (player.isPlaying && lastPlayedAudioId == item.createdAt) {
                    stopPlaying()
                } else {
                    startPlaying(context, item, binding)
                }
            }

            binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && lastPlayedAudioId == item.createdAt) {
                        player.seekTo(progress)
                        binding.txtDuration.text = Utils.formatDuration(progress.toLong())
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            binding.executePendingBindings()
        }

        private fun startPlaying(context: Context, item: GroupMessage, holder: RowGroupAudioReceiveBinding) {
            stopPlaying()
            lastPlayedReceivedHolder = holder
            lastPlayedSentHolder = null
            lastPlayedAudioId = item.createdAt
            
            holder.progressBuffer.show()
            holder.imgPlay.gone()
            
            player.apply {
                try {
                    setDataSource(context, Uri.parse(item.audioMessage?.uri))
                    prepareAsync()
                    setOnPreparedListener {
                        start()
                        holder.progressBuffer.gone()
                        holder.imgPlay.setImageResource(R.drawable.ic_action_stop)
                        holder.imgPlay.show()
                        handler.post(updateSeekBar)
                        EventBus.getDefault().post(EventAudioMsg(true))
                    }
                    setOnCompletionListener { stopPlaying() }
                } catch (e: IOException) {
                    e.printStackTrace()
                    stopPlaying()
                }
            }
        }
    }

    class AudioSentVHolder(val binding: RowGroupAudioSentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(context: Context, item: GroupMessage) {
            binding.message = item
            val durationMs = (item.audioMessage?.duration ?: 0) * 1000
            binding.seekBar.max = durationMs
            binding.seekBar.progress = 0
            binding.txtDuration.text = Utils.formatDuration(durationMs.toLong())
            
            binding.imgPlay.setOnClickListener {
                if (player.isPlaying && lastPlayedAudioId == item.createdAt) {
                    stopPlaying()
                } else {
                    startPlaying(context, item, binding)
                }
            }

            binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && lastPlayedAudioId == item.createdAt) {
                        player.seekTo(progress)
                        binding.txtDuration.text = Utils.formatDuration(progress.toLong())
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            binding.executePendingBindings()
        }

        private fun startPlaying(context: Context, item: GroupMessage, holder: RowGroupAudioSentBinding) {
            stopPlaying()
            lastPlayedSentHolder = holder
            lastPlayedReceivedHolder = null
            lastPlayedAudioId = item.createdAt
            
            holder.progressBuffer.show()
            holder.imgPlay.gone()
            
            player.apply {
                try {
                    setDataSource(context, Uri.parse(item.audioMessage?.uri))
                    prepareAsync()
                    setOnPreparedListener {
                        start()
                        holder.progressBuffer.gone()
                        holder.imgPlay.setImageResource(R.drawable.ic_action_stop)
                        holder.imgPlay.show()
                        handler.post(updateSeekBar)
                        EventBus.getDefault().post(EventAudioMsg(true))
                    }
                    setOnCompletionListener { stopPlaying() }
                } catch (e: IOException) {
                    e.printStackTrace()
                    stopPlaying()
                }
            }
        }
    }
}

class DiffCallbackMessages : DiffUtil.ItemCallback<GroupMessage>() {
    override fun areItemsTheSame(oldItem: GroupMessage, newItem: GroupMessage): Boolean = oldItem.createdAt == newItem.createdAt
    override fun areContentsTheSame(oldItem: GroupMessage, newItem: GroupMessage): Boolean = oldItem == newItem
}
