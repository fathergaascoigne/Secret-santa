package fr.secretsanta.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.text.InputType
import android.view.Gravity
import android.widget.*
import java.security.SecureRandom
import java.util.UUID

private data class Person(val name: String, val phone: String)

class MainActivity : Activity() {
    private val people = mutableListOf<Person>()
    private val blocked = mutableSetOf<Pair<Int, Int>>()
    private val random = SecureRandom()
    private lateinit var list: LinearLayout
    private lateinit var exclusions: LinearLayout
    private lateinit var budget: EditText
    private lateinit var greeting: EditText
    private lateinit var status: TextView
    private lateinit var sendButton: Button
    private var running = false
    private var awaitingPermission = false
    private var receiver: BroadcastReceiver? = null
    private var expected = 0
    private var completed = 0
    private var failed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(20, 69, 48)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(Color.rgb(247, 249, 246))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        fun heading(t: String, size: Float = 20f) {
            root.addView(TextView(this).apply {
                text = t; textSize = size; setTextColor(Color.rgb(20, 69, 48))
                setPadding(0, 14, 0, 12)
            })
        }
        heading("🎄 Secret Santa chez Marvin & Sophie", 26f)
        heading("26 décembre 2026", 17f)
        heading("Participants")
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(Button(this).apply {
            text = "+ Ajouter un participant"
            setOnClickListener { if (!running) addPerson() }
        })
        root.addView(Button(this).apply {
            text = "📱 Choisir dans mes contacts"
            setOnClickListener {
                if (!running) {
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                            201
                        )
                    } catch (_: Exception) {
                        toast("Impossible d'ouvrir les contacts sur cet appareil")
                    }
                }
            }
        })
        heading("Exclusions entre participants")
        exclusions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(exclusions)
        heading("Budget (€)")
        budget = EditText(this).apply {
            hint = "Ex. 25"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(budget)
        heading("Message personnalisé")
        greeting = EditText(this).apply { hint = "Joyeux Noël !"; minLines = 2 }
        root.addView(greeting)
        sendButton = Button(this).apply {
            text = "🎁 Tirer au sort et envoyer"
            setOnClickListener { confirmSend() }
        }
        root.addView(sendButton)
        status = TextView(this).apply {
            text = "Aucun tirage effectué. Les résultats ne seront pas affichés."
            textSize = 15f; setPadding(0, 20, 0, 12)
        }
        root.addView(status)
        heading("Envoi depuis votre SIM : des frais et traces opérateur sont possibles.", 13f)
        refresh()
    }

    private fun addPerson() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0)
        }
        val name = EditText(this).apply { hint = "Prénom"; setSingleLine() }
        val phone = EditText(this).apply {
            hint = "Numéro mobile (+33...)"; inputType = InputType.TYPE_CLASS_PHONE
        }
        form.addView(name); form.addView(phone)
        AlertDialog.Builder(this).setTitle("Ajouter un participant").setView(form)
            .setPositiveButton("Ajouter") { _, _ ->
                val n = name.text.toString().trim()
                val p = phone.text.toString().replace(Regex("[ .()-]"), "")
                if (n.isBlank() || !p.matches(Regex("\\+?[0-9]{8,15}"))) {
                    toast("Prénom ou numéro invalide")
                } else if (people.any { it.phone == p }) {
                    toast("Numéro déjà présent")
                } else {
                    people.add(Person(n, p)); refresh()
                }
            }.setNegativeButton("Annuler", null).show()
    }

    @Deprecated("Android activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 201 || resultCode != Activity.RESULT_OK || running) return
        val uri = data?.data ?: return
        try {
            contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    toast("Aucun numéro trouvé pour ce contact")
                    return
                }
                val rawName = cursor.getString(0).orEmpty().trim()
                val rawPhone = cursor.getString(1).orEmpty()
                val normalized = rawPhone.replace(Regex("[^+0-9]"), "")
                if (rawName.isBlank() || !normalized.matches(Regex("\\\\+?[0-9]{8,15}"))) {
                    toast("Nom ou numéro invalide : ajoute ce participant manuellement")
                    return
                }
                val name = rawName.substringBefore(" ").ifBlank { rawName }
                if (people.any { it.phone == normalized }) {
                    toast("Ce numéro est déjà dans les participants")
                    return
                }
                if (people.size >= 30) {
                    toast("Maximum 30 participants")
                    return
                }
                people.add(Person(name, normalized))
                refresh()
                toast("$name ajouté aux participants")
            }
        } catch (_: SecurityException) {
            toast("Accès au contact refusé")
        } catch (_: Exception) {
            toast("Impossible de lire ce contact")
        }
    }

    private fun refresh() {
        list.removeAllViews(); exclusions.removeAllViews()
        people.forEachIndexed { i, person ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "${person.name} · ${person.phone}"; textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(Button(this).apply {
                text = "✕"; isEnabled = !running
                setOnClickListener {
                    people.removeAt(i)
                    val remap = blocked.mapNotNull { (a, b) ->
                        if (a == i || b == i) null
                        else Pair(if (a > i) a - 1 else a, if (b > i) b - 1 else b)
                    }
                    blocked.clear(); blocked.addAll(remap); refresh()
                }
            })
            list.addView(row)
        }
        for (i in people.indices) for (j in i + 1 until people.size) {
            val a = i; val b = j
            exclusions.addView(CheckBox(this).apply {
                text = "${people[a].name} ↔ ${people[b].name}"
                isChecked = Pair(a, b) in blocked; isEnabled = !running
                setOnCheckedChangeListener { _, yes ->
                    if (yes) blocked.add(Pair(a, b)) else blocked.remove(Pair(a, b))
                }
            })
        }
        sendButton.isEnabled = !running
    }

    private fun draw(): IntArray? {
        val n = people.size
        if (n < 2) return null
        val used = BooleanArray(n)
        val assignment = IntArray(n) { -1 }
        val order = (0 until n).sortedBy { i ->
            (0 until n).count { j ->
                i != j && Pair(minOf(i, j), maxOf(i, j)) !in blocked
            }
        }
        var visits = 0
        fun search(depth: Int): Boolean {
            if (++visits > 2_000_000) return false
            if (depth == n) return true
            val giver = order[depth]
            val options = (0 until n).filter { j ->
                !used[j] && giver != j && Pair(minOf(giver, j), maxOf(giver, j)) !in blocked
            }.toMutableList()
            for (i in options.lastIndex downTo 1) {
                val j = random.nextInt(i + 1)
                val tmp = options[i]; options[i] = options[j]; options[j] = tmp
            }
            for (j in options) {
                used[j] = true; assignment[giver] = j
                if (search(depth + 1)) return true
                used[j] = false; assignment[giver] = -1
            }
            return false
        }
        return if (search(0)) assignment else null
    }

    private fun confirmSend() {
        if (running) return
        if (people.size !in 2..30) {
            toast("Il faut entre 2 et 30 participants"); return
        }
        AlertDialog.Builder(this).setTitle("Confirmer l'envoi ?")
            .setMessage("Un SMS sera envoyé aux ${people.size} participants depuis votre SIM. Les attributions resteront secrètes. En cas d'échec partiel, ne relancez pas de nouveau tirage.")
            .setPositiveButton("Envoyer") { _, _ -> checkPermission() }
            .setNegativeButton("Annuler", null).show()
    }

    private fun checkPermission() {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            awaitingPermission = true
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 100)
        } else send()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 100 && awaitingPermission) {
            awaitingPermission = false
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) send()
            else toast("Autorisation SMS refusée")
        }
    }

    private fun send() {
        val assignment = draw()
        if (assignment == null) {
            AlertDialog.Builder(this).setMessage("Aucun tirage valide. Réduisez les exclusions.")
                .setPositiveButton("OK", null).show()
            return
        }
        val sms = try {
            val mgr = getSystemService(SmsManager::class.java)
            if (Build.VERSION.SDK_INT >= 31) {
                val sub = SubscriptionManager.getDefaultSmsSubscriptionId()
                if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                    throw IllegalStateException("Choisissez une SIM SMS par défaut")
                mgr.createForSubscriptionId(sub)
            } else SmsManager.getDefault()
        } catch (e: Exception) {
            toast("SIM SMS indisponible : ${e.message}"); return
        }
        val money = budget.text.toString().trim()
        val note = greeting.text.toString().trim()
        val tasks = people.indices.map { i ->
            val message = "🎅 SECRET SANTA CHEZ MARVIN & SOPHIE 🎄\\n📅 26 décembre 2026\\n\\n" +
                "Ho ho ho, ${people[i].name} ! 🎁\\n" +
                "Cette année, tu dois offrir un cadeau à ${people[assignment[i]].name} !" +
                (if (money.isNotBlank()) "\\n🎁 Budget : $money €" else "") +
                (if (note.isNotBlank()) "\\n$note" else "") +
                "\\n\\n🤫 Tirage réalisé automatiquement par l'application Secret Santa. " +
                "Chacun reçoit uniquement sa propre attribution. Garde le secret jusqu'au 26 décembre ! 🎄"
            Pair(people[i].phone, sms.divideMessage(message))
        }
        running = true; refresh()
        expected = tasks.sumOf { it.second.size }
        completed = 0; failed = 0
        val action = "$packageName.SMS_SENT.${UUID.randomUUID()}"
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != action) return
                completed++
                if (resultCode != Activity.RESULT_OK) failed++
                status.text = "Segments SMS traités : $completed / $expected · Échecs : $failed"
                if (completed >= expected) finishSending()
            }
        }
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, IntentFilter(action))
        status.text = "Envoi en cours : 0 / $expected segments SMS"
        tasks.forEachIndexed { i, (number, parts) ->
            try {
                val intents = ArrayList<PendingIntent>()
                parts.indices.forEach { part ->
                    val intent = Intent(action).setPackage(packageName)
                    intents.add(PendingIntent.getBroadcast(
                        this, i * 100 + part, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    ))
                }
                sms.sendMultipartTextMessage(number, null, parts, intents, null)
            } catch (_: Exception) {
                completed += parts.size; failed += parts.size
            }
        }
        if (completed >= expected && running) finishSending()
    }

    private fun finishSending() {
        receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null; running = false
        val message = if (failed == 0)
            "Tous les segments SMS ont été acceptés par le réseau. Réception non garantie. Aucun tirage sauvegardé."
        else
            "$failed segment(s) en échec sur $expected. Ne relancez pas de tirage : certains destinataires ont peut-être reçu leur SMS."
        status.text = message
        refresh()
        AlertDialog.Builder(this).setTitle("Envoi terminé")
            .setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
        super.onDestroy()
    }
}
