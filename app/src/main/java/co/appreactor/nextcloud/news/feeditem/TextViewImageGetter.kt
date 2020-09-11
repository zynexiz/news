package co.appreactor.nextcloud.news.feeditem

import android.graphics.drawable.Drawable
import android.text.Html
import android.widget.TextView
import com.squareup.picasso.Picasso

class TextViewImageGetter(private val textView: TextView) : Html.ImageGetter {

    override fun getDrawable(source: String): Drawable {
        val drawable = TextViewImage(textView)

        Picasso.get()
            .load(source)
            .resize(textView.width, 0)
            .into(drawable)

        return drawable
    }
}
