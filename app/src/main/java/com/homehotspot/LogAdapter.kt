package com.homehotspot

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class LogAdapter : ListAdapter<LogEntry, LogAdapter.LogViewHolder>(DiffCallback) {

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvLogTimestamp)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvLogStatus)
        private val tvAction: TextView = itemView.findViewById(R.id.tvLogAction)
        private val tvDetails: TextView = itemView.findViewById(R.id.tvLogDetails)

        fun bind(entry: LogEntry) {
            tvTimestamp.text = entry.timestamp
            tvAction.text = entry.action
            tvDetails.text = entry.details
            tvStatus.text = entry.status.name

            val statusColorRes = when (entry.status) {
                LogStatus.INFO -> R.color.log_info
                LogStatus.SUCCESS -> R.color.log_success
                LogStatus.FAILED -> R.color.log_failed
                LogStatus.UNAVAILABLE -> R.color.log_unavailable
            }
            tvStatus.background.setTint(ContextCompat.getColor(itemView.context, statusColorRes))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.log_item, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
                return oldItem.timestamp == newItem.timestamp && oldItem.action == newItem.action
            }

            override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
                return oldItem == newItem
            }
        }
    }
}
