package com.adit.iptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    private val items = mutableListOf<Channel>()
    private var favorites: Set<String> = emptySet()
    private var playingUrl: String? = null

    fun submit(list: List<Channel>, favs: Set<String>, playing: String?) {
        items.clear()
        items.addAll(list)
        favorites = favs
        playingUrl = playing
        notifyDataSetChanged()
    }

    fun setPlaying(url: String?) {
        playingUrl = url
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.chLogo)
        val logoEmoji: TextView = view.findViewById(R.id.chLogoEmoji)
        val name: TextView = view.findViewById(R.id.chName)
        val group: TextView = view.findViewById(R.id.chGroup)
        val fav: TextView = view.findViewById(R.id.chFav)
        val playingBar: View = view.findViewById(R.id.chPlayingBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = items[position]

        holder.name.text = channel.name
        holder.group.text = channel.group.ifBlank { "Live TV" }
        holder.fav.text = if (favorites.contains(channel.url)) "★" else "☆"
        holder.playingBar.visibility = if (channel.url == playingUrl) View.VISIBLE else View.GONE

        if (channel.logo.startsWith("http")) {
            holder.logoEmoji.visibility = View.GONE
            holder.logo.visibility = View.VISIBLE
            holder.logo.load(channel.logo) {
                crossfade(true)
            }
        } else {
            holder.logo.visibility = View.GONE
            holder.logoEmoji.visibility = View.VISIBLE
            holder.logoEmoji.text = channel.logo.ifBlank { "📺" }
        }

        holder.itemView.setOnClickListener { onClick(channel) }
        holder.fav.setOnClickListener { onToggleFavorite(channel) }
        holder.itemView.setOnLongClickListener {
            onToggleFavorite(channel)
            true
        }
    }
}
