package br.dev.nexus.gravadorocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import br.dev.nexus.gravadorocr.core.Detection
import br.dev.nexus.gravadorocr.core.TimeFmt
import br.dev.nexus.gravadorocr.databinding.ItemDetectionBinding
import java.util.concurrent.Executors

class DetectionAdapter(
    private val ctx: Context,
    private val onClick: (Detection) -> Unit,
) : RecyclerView.Adapter<DetectionAdapter.VH>() {

    class VH(val b: ItemDetectionBinding) : RecyclerView.ViewHolder(b.root)

    private var items: List<Detection> = emptyList()
    private val thumbs = LruCache<String, Bitmap>(40)
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun submit(list: List<Detection>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        val time = TimeFmt.clock(d.sessionOffsetMs)
        val b = holder.b
        when (d.kind) {
            Detection.Kind.FOUND -> {
                b.title.text = "$time  •  ${d.label}"
                b.title.setTextColor(ContextCompat.getColor(ctx, R.color.ok))
                b.subtitle.text = "Lido: \"${d.snippet}\"  (parte ${d.segment})"
            }
            Detection.Kind.MARK -> {
                b.title.text = "$time  •  Marcação"
                b.title.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
                b.subtitle.text = "Parte ${d.segment}"
            }
            Detection.Kind.WARNING -> {
                b.title.text = "$time  •  Aviso"
                b.title.setTextColor(ContextCompat.getColor(ctx, R.color.warn))
                b.subtitle.text = d.label
            }
        }
        b.root.setOnClickListener { onClick(d) }
        b.thumb.setImageDrawable(null)
        val uri = d.snapshotUri
        b.thumb.tag = uri
        if (uri != null) {
            val cached = thumbs.get(uri)
            if (cached != null) {
                b.thumb.setImageBitmap(cached)
            } else {
                loader.execute {
                    val bmp = runCatching {
                        ctx.contentResolver.loadThumbnail(Uri.parse(uri), Size(192, 192), null)
                    }.getOrNull() ?: return@execute
                    thumbs.put(uri, bmp)
                    main.post { if (b.thumb.tag == uri) b.thumb.setImageBitmap(bmp) }
                }
            }
        }
    }
}
