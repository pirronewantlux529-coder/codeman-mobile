package top.zzcoding.codeman

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MachinesActivity : AppCompatActivity() {

    private lateinit var machines: MutableList<Machine>
    private lateinit var adapter: MachineAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_machines)
        title = getString(R.string.menu_machines)

        machines = MachineStore.load(this)
        adapter = MachineAdapter()

        findViewById<RecyclerView>(R.id.machine_list).apply {
            layoutManager = LinearLayoutManager(this@MachinesActivity)
            adapter = this@MachinesActivity.adapter
        }
        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener { showEditDialog(null) }
    }

    private fun persist() {
        MachineStore.save(this, machines)
        adapter.notifyDataSetChanged()
    }

    private fun showEditDialog(existing: Machine?) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_machine, null)
        val name = v.findViewById<EditText>(R.id.edit_name)
        val host = v.findViewById<EditText>(R.id.edit_host)
        val port = v.findViewById<EditText>(R.id.edit_port)
        val user = v.findViewById<EditText>(R.id.edit_user)
        val pass = v.findViewById<EditText>(R.id.edit_pass)
        val https = v.findViewById<CheckBox>(R.id.check_https)

        if (existing != null) {
            name.setText(existing.name)
            host.setText(existing.host)
            port.setText(existing.port.toString())
            user.setText(existing.username)
            pass.setText(existing.password)
            https.isChecked = existing.useHttps
        } else {
            port.setText("8095")
            user.setText("admin")
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_machine else R.string.edit_machine)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                val m = existing ?: Machine(
                    id = System.currentTimeMillis(), name = "", host = "",
                    port = 8095, useHttps = false, username = "admin", password = ""
                ).also { machines.add(it) }
                m.name = name.text.toString().ifBlank { host.text.toString() }
                m.host = host.text.toString().trim()
                m.port = port.text.toString().toIntOrNull() ?: 8095
                m.username = user.text.toString().trim()
                m.password = pass.text.toString()
                m.useHttps = https.isChecked
                if (machines.size == 1) MachineStore.setSelected(this, m.id)
                persist()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    inner class MachineAdapter : RecyclerView.Adapter<MachineAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.machine_name)
            val detail: TextView = view.findViewById(R.id.machine_detail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_machine, parent, false)
        )

        override fun getItemCount() = machines.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = machines[position]
            val isSelected = MachineStore.selectedId(this@MachinesActivity) == m.id
            holder.name.text = (if (isSelected) "✓ " else "") + m.name
            holder.detail.text = m.baseUrl
            holder.itemView.setOnClickListener {
                MachineStore.setSelected(this@MachinesActivity, m.id)
                notifyDataSetChanged()
                finish()
            }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@MachinesActivity)
                    .setItems(arrayOf(getString(R.string.edit_machine), getString(R.string.delete))) { _, which ->
                        when (which) {
                            0 -> showEditDialog(m)
                            1 -> {
                                machines.remove(m)
                                persist()
                            }
                        }
                    }
                    .show()
                true
            }
        }
    }
}
