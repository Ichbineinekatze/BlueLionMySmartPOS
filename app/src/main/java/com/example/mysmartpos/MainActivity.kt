package com.bluelion.mysmartpos

import android.content.Context
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

// --- 1. VERİTABANI (Aynı Kalıyor) ---
@Entity(tableName = "sales")
data class Sale(@PrimaryKey(autoGenerate = true) val id: Int = 0, val amount: String, val cardId: String, val date: String)

@Dao
interface SaleDao {
    @Insert suspend fun insert(sale: Sale)
    @Query("SELECT * FROM sales ORDER BY id DESC") suspend fun getAllSales(): List<Sale>
    @Query("DELETE FROM sales") suspend fun deleteAll()
    @Query("SELECT COALESCE(SUM(CAST(amount AS DOUBLE)), 0.0) FROM sales") suspend fun getTotalRevenue(): Double
}

@Database(entities = [Sale::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun saleDao(): SaleDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "blue_lion_pos_db").fallbackToDestructiveMigration().build()
            INSTANCE = instance
            instance
        }
    }
}

// --- 2. ANA EKRAN ---
class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var db: AppDatabase
    private var inputAmount by mutableStateOf("")
    private var totalRevenue by mutableStateOf(0.0)
    private val salesList = mutableStateListOf<Sale>()
    private var isSheetOpen by mutableStateOf(false)
    private var isSuccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        db = AppDatabase.getDatabase(this)
        refreshData()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF007AFF),
                background = Color.Black,
                surface = Color(0xFF1C1C1E)
            )) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                        PosContent(Modifier.padding(padding))
                    }
                }
            }
        }
    }

    private fun refreshData() {
        CoroutineScope(Dispatchers.IO).launch {
            val list = db.saleDao().getAllSales()
            val total = db.saleDao().getTotalRevenue()
            withContext(Dispatchers.Main) {
                salesList.clear()
                salesList.addAll(list)
                totalRevenue = total
            }
        }
    }

    // --- YENİ EKLEME: GRAFİK BİLEŞENİ ---
    @Composable
    fun ChartContent(sales: List<Sale>) {
        val lastSales = sales.take(5).reversed() // Son 5 satışı al ve kronolojik diz
        val maxAmount = lastSales.maxOfOrNull { it.amount.toDoubleOrNull() ?: 1.0 } ?: 1.0

        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("SON İŞLEM ANALİZİ (₺)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                lastSales.forEach { sale ->
                    val amount = sale.amount.toDoubleOrNull() ?: 0.0
                    val ratio = (amount / maxAmount).toFloat().coerceAtLeast(0.1f) // En az minik bir bar görünsün

                    val animatedHeight by animateFloatAsState(
                        targetValue = ratio,
                        animationSpec = tween(durationMillis = 1000), label = ""
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .fillMaxHeight(animatedHeight)
                                .background(Color(0xFF007AFF), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        )
                        Text(sale.date.take(5), fontSize = 8.sp, color = Color.Gray) // Sadece HH:mm kısmını al
                    }
                }
            }
        }
    }

    @Composable
    fun PosContent(modifier: Modifier = Modifier) {
        if (isSheetOpen) {
            AlertDialog(
                onDismissRequest = { if (!isSuccess) isSheetOpen = false },
                confirmButton = { },
                containerColor = Color(0xFF1C1C1E),
                title = {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isSuccess) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(64.dp), Color.Green)
                            Text("BAŞARILI", color = Color.Green, fontWeight = FontWeight.Bold)
                        } else {
                            Text("KARTI YAKLAŞTIRIN", color = Color.White)
                        }
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("₺$inputAmount", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White)
                        if (!isSuccess) {
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF007AFF))
                        }
                    }
                }
            )
        }

        Column(modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("BLUE LION POS", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
                IconButton(onClick = { CoroutineScope(Dispatchers.IO).launch { db.saleDao().deleteAll(); refreshData() } }) {
                    Icon(Icons.Default.Delete, null, tint = Color.Red.copy(alpha = 0.5f))
                }
            }

            // --- GÜNCELLENMİŞ CİRO KARTI (GRAFİK DAHİL) ---
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))) {
                Column(Modifier.padding(16.dp)) {
                    Text("Günlük Ciro", color = Color.Gray, fontSize = 12.sp)
                    Text("₺${String.format("%.2f", totalRevenue)}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)

                    if (salesList.isNotEmpty()) {
                        Divider(Modifier.padding(vertical = 12.dp), color = Color.DarkGray, thickness = 0.5.dp)
                        ChartContent(salesList) // Grafiği burada çağırıyoruz
                    }
                }
            }

            OutlinedTextField(
                value = inputAmount,
                onValueChange = { if (it.all { char -> char.isDigit() || char == '.' }) inputAmount = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tutar Girin") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                onClick = { if (inputAmount.isNotEmpty()) { isSheetOpen = true; isSuccess = false } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ShoppingCart, null)
                Spacer(Modifier.width(8.dp))
                Text("ÖDEME AL", fontWeight = FontWeight.Bold)
            }

            Text("İŞLEM GEÇMİŞİ", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)

            LazyColumn(Modifier.weight(1f)) {
                items(salesList) { sale ->
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("₺${sale.amount}", fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Kart: ${sale.cardId}", fontSize = 10.sp, color = Color.Gray)
                        }
                        Text(sale.date, color = Color.Gray, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(this, { tag ->
            if (isSheetOpen && !isSuccess) {
                val id = tag.id.joinToString("") { "%02X".format(it) }
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                runOnUiThread {
                    isSuccess = true
                    CoroutineScope(Dispatchers.IO).launch {
                        db.saleDao().insert(Sale(amount = inputAmount, cardId = id, date = time))
                        refreshData()
                        delay(1500)
                        withContext(Dispatchers.Main) {
                            isSheetOpen = false
                            inputAmount = ""
                            isSuccess = false
                        }
                    }
                }
            }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    override fun onPause() { super.onPause(); nfcAdapter?.disableReaderMode(this) }
}