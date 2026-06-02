package com.industrialble.tools

/**
 * Generador de wordlists basado en información personal.
 * Crea combinaciones de contraseñas probables a partir de datos ingresados por el usuario.
 */
class WordlistGenerator {

    data class PersonalInfo(
        val nombres: String = "",
        val apellidos: String = "",
        val apodo: String = "",
        val fechaNacimiento: String = "", // DD/MM/AAAA
        val telefono: String = "",
        val empresa: String = "",
        val mascota: String = "",
        val ciudad: String = "",
        val palabrasClave: List<String> = emptyList()
    )

    /** Genera una wordlist completa a partir de la información personal */
    fun generate(info: PersonalInfo): List<String> {
        val words = mutableSetOf<String>()
        val baseWords = extractBaseWords(info)
        val dates = extractDates(info.fechaNacimiento)

        // 1. Palabras base
        words.addAll(baseWords)

        // 2. Capitalizaciones y variaciones
        for (word in baseWords) {
            words.add(word.lowercase())
            words.add(word.uppercase())
            words.add(word.replaceFirstChar { it.uppercase() })
            words.add(word.replaceFirstChar { it.lowercase() })
            words.add(word.reversed())
            words.add(leetSpeak(word))
        }

        // 3. Combinaciones con años
        for (word in baseWords) {
            for (year in dates) {
                words.add("$word$year")
                words.add("$year$word")
                words.add("${word}_$year")
                words.add("${word}.$year")
                words.add("${word}-$year")
                words.add("${word}@$year")
                words.add("${word}#$year")
            }
        }

        // 4. Combinaciones de 2 palabras
        for (i in baseWords.indices) {
            for (j in baseWords.indices) {
                if (i != j) {
                    words.add("${baseWords[i]}${baseWords[j]}")
                    words.add("${baseWords[i]}_${baseWords[j]}")
                    words.add("${baseWords[i]}.${baseWords[j]}")
                    words.add("${baseWords[i]}-${baseWords[j]}")
                    words.add("${baseWords[i]}@${baseWords[j]}")
                }
            }
        }

        // 5. Años comunes + sufijos
        for (year in dates) {
            words.add(year)
            words.add("$year!")
            words.add("$year.")
            words.add("$year@")
            words.add("$year#")
            words.add("$year$")
            words.add("Contraseña$year")
            words.add("password$year")
            words.add("admin$year")
            words.add("$year!")

            for (word in baseWords) {
                words.add("${word}123")
                words.add("${word}123!")
                words.add("${word}123.")
                words.add("${word}123@")
                words.add("${word}123#")
                words.add("${word}123$")
                words.add("${word}1234")
                words.add("${word}1234!")
                words.add("${word}12345")
                words.add("${word}2023")
                words.add("${word}2024")
                words.add("${word}2025")
                words.add("${word}2026")
            }
        }

        // 6. Patrones comunes con números
        for (word in baseWords) {
            for (i in 0..9) {
                words.add("$word$i")
                words.add("$word$i$i")
                words.add("$word$i$i$i")
                words.add("${word}_$i")
                words.add("${word}.$i")
            }
        }

        // 7. Sufijos de seguridad comunes
        val suffixes = listOf("!", "@", "#", "$", "%", "&", "*", ".", "_", "-",
            "123", "1234", "12345", "123456",
            "01", "02", "03",
            "1", "2", "3",
            "segura", "Segura", "SEGURA",
            "seguro", "Seguro", "SEGURO",
            "key", "Key", "KEY",
            "pass", "Pass", "PASS",
            "admin", "Admin", "ADMIN")

        for (word in baseWords) {
            for (suffix in suffixes) {
                words.add("$word$suffix")
            }
        }

        // 8. Palabras clave comunes
        val commonPasswords = listOf(
            "password", "Password", "PASSWORD",
            "admin", "Admin", "ADMIN",
            "12345678", "123456789", "1234567890",
            "qwerty123", "qwerty1234",
            "abc123", "ABC123",
            "letmein", "welcome", "Welcome",
            "iloveyou", "monkey", "dragon",
            "master", "Master", "MASTER",
            "superman", "batman", "princess",
            "sunshine", "trustno1"
        )
        words.addAll(commonPasswords)

        return words.toList().shuffled()
    }

    /** Genera wordlist gigante con mutaciones */
    fun generateWithMutations(info: PersonalInfo, maxSize: Int = 100000): List<String> {
        val base = generate(info).toMutableList()

        // Si ya tenemos suficientes, devolver
        if (base.size >= maxSize) return base.take(maxSize)

        // Mutaciones adicionales
        val extra = mutableSetOf<String>()
        for (word in base) {
            if (extra.size >= maxSize) break

            // Reemplazar letras por números
            extra.add(word.replace('a', '4').replace('e', '3').replace('i', '1').replace('o', '0').replace('s', '5'))
            extra.add(word.replace('a', '@').replace('s', '$').replace('i', '!'))
            extra.add(word.replace('l', '1').replace('z', '2').replace('g', '9'))

            // Variaciones de mayúsculas
            if (word.length >= 2) {
                extra.add(word.uppercase())
                extra.add(word.lowercase())
                extra.add(word.replaceFirstChar { it.uppercase() })
            }

            // Añadir/remover caracteres especiales
            extra.add("$word!!")
            extra.add("$word??")
            extra.add("$word...")
            extra.add("$word###")
            extra.add("$word$$$")
        }

        val combined = (base + extra).toList().shuffled()
        return if (combined.size > maxSize) combined.take(maxSize) else combined
    }

    private fun extractBaseWords(info: PersonalInfo): List<String> {
        val words = mutableSetOf<String>()

        // Nombres y apellidos
        info.nombres.split(" ", ",").filter { it.length >= 2 }.forEach { words.add(it.trim()) }
        info.apellidos.split(" ", ",").filter { it.length >= 2 }.forEach { words.add(it.trim()) }
        if (info.apodo.isNotBlank()) words.add(info.apodo.trim())

        // Combinaciones de nombre + apellido
        val nombres = info.nombres.split(" ", ",").map { it.trim() }.filter { it.length >= 2 }
        val apellidos = info.apellidos.split(" ", ",").map { it.trim() }.filter { it.length >= 2 }

        if (nombres.isNotEmpty() && apellidos.isNotEmpty()) {
            words.add("${nombres.first()}${apellidos.first()}")
            words.add("${apellidos.first()}${nombres.first()}")
            if (nombres.size > 1 && apellidos.size > 1) {
                words.add("${nombres.first()}${apellidos.last()}")
                words.add("${nombres.last()}${apellidos.first()}")
            }
        }

        // Teléfono
        val tel = info.telefono.filter { it.isDigit() }
        if (tel.length >= 7) {
            words.add(tel)
            words.add(tel.takeLast(8))
            words.add(tel.takeLast(6))
            words.add(tel.takeLast(4))
        }

        // Empresa
        if (info.empresa.isNotBlank()) {
            words.add(info.empresa.trim())
            words.add(info.empresa.uppercase().trim())
            val empParts = info.empresa.split(" ", ",", ".", "-")
            if (empParts.size >= 2) {
                words.addAll(empParts.filter { it.length >= 2 }.map { it.trim() })
                words.add(empParts.joinToString("") { it.take(1) })
            }
        }

        // Mascota
        if (info.mascota.isNotBlank()) words.add(info.mascota.trim())

        // Ciudad
        if (info.ciudad.isNotBlank()) words.add(info.ciudad.trim())

        // Palabras clave
        words.addAll(info.palabrasClave.map { it.trim() }.filter { it.length >= 2 })

        return words.toList()
    }

    private fun extractDates(fecha: String): List<String> {
        val dates = mutableListOf<String>()
        val digits = fecha.filter { it.isDigit() }

        // DDMMAAAA, DDMMAA, AAAA, AA
        when {
            digits.length == 8 -> {
                val year = digits.substring(4)
                val month = digits.substring(2, 4)
                val day = digits.substring(0, 2)
                dates.add(year)
                dates.add(digits.substring(2)) // DDMMAA -> but as MMDDAA
                dates.add(day + month)
                dates.add(month + day)
                dates.addAll(listOf(
                    day, month,
                    "$day$month", "$month$day",
                    "${day}_${month}", "${month}_${day}"
                ))
            }
            digits.length == 6 -> {
                dates.add(digits)
                dates.add("20$digits")
                dates.add(digits.substring(4))
                dates.add(digits.substring(0, 2))
                dates.add(digits.substring(2, 4))
            }
            digits.length == 4 -> {
                dates.add(digits)
                dates.add(digits.substring(2))
            }
            digits.length == 2 -> {
                dates.add(digits)
                dates.add("20$digits")
                dates.add("19$digits")
            }
        }

        // Años comunes
        val currentYear = 2025
        for (y in (currentYear - 10)..currentYear) {
            dates.add(y.toString())
            dates.add(y.toString().substring(2))
        }

        return dates.distinct()
    }

    private fun leetSpeak(word: String): String {
        return word
            .replace('a', '4')
            .replace('e', '3')
            .replace('i', '1')
            .replace('o', '0')
            .replace('s', '5')
            .replace('t', '7')
            .replace('b', '8')
            .replace('g', '9')
    }
}
