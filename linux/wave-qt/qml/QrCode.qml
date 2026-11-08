import QtQuick
import wave

// QR（行ごとの「1」黒・「0」白の文字列）を Canvas で描く（software の描き方でも描ける）。周りに白い余白を4目分とる。
Canvas {
    id: canvas
    property var rows: []
    implicitWidth: 220
    implicitHeight: 220
    onRowsChanged: requestPaint()
    onPaint: {
        const ctx = getContext("2d")
        ctx.reset()
        ctx.fillStyle = "white"
        ctx.fillRect(0, 0, width, height)
        const n = rows.length
        if (n === 0) return
        const cell = Math.floor(Math.min(width, height) / (n + 8))
        const offset = Math.floor((Math.min(width, height) - cell * n) / 2)
        ctx.fillStyle = "black"
        for (let y = 0; y < n; y++)
            for (let x = 0; x < n; x++)
                if (rows[y][x] === "1") ctx.fillRect(offset + x * cell, offset + y * cell, cell, cell)
    }
}
