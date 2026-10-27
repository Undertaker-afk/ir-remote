package app.irremote

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import app.irremote.data.QuickSend

/** Invisible activity: sends one key and finishes. Accepts extras `remote`/`key` or ir-remote://send?remote=..&key=.. */
class SendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val remote = intent.getStringExtra("remote") ?: uri?.getQueryParameter("remote")
        val key = intent.getStringExtra("key") ?: uri?.getQueryParameter("key")
        QuickSend.run(this, remote, key)?.let {
            Toast.makeText(this, getString(R.string.quick_failed, it), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
