package com.codeeditor.app.connections

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.codeeditor.app.R

class ConnectionAdapter(
    private val onItemClick: (ConnectionEntity) -> Unit,
    private val onTerminalClick: (ConnectionEntity) -> Unit,
    private val onMoreClick: (ConnectionEntity, View) -> Unit
) : ListAdapter<ConnectionEntity, ConnectionAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_connection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.connectionName)
        private val detailsText: TextView = itemView.findViewById(R.id.connectionDetails)
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)
        private val btnTerminal: ImageButton = itemView.findViewById(R.id.btnTerminal)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)

        fun bind(connection: ConnectionEntity) {
            nameText.text = connection.name
            detailsText.text = connection.displayAddress

            val bg = statusIndicator.background
            if (bg is GradientDrawable) {
                bg.setColor(itemView.context.getColor(R.color.status_disconnected))
                bg.cornerRadius = 100f
            }

            itemView.setOnClickListener { onItemClick(connection) }
            btnTerminal.setOnClickListener { onTerminalClick(connection) }
            btnMore.setOnClickListener { onMoreClick(connection, it) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ConnectionEntity>() {
        override fun areItemsTheSame(oldItem: ConnectionEntity, newItem: ConnectionEntity) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ConnectionEntity, newItem: ConnectionEntity) =
            oldItem == newItem
    }
}
