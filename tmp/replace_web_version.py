import sys

with open('app/src/main/java/com/example/webserver/AdminWebServer.kt', 'r') as f:
    lines = f.readlines()

print('Length before:', len(lines))

replacement = """    private fun handleWebVersion(socket: Socket) {
        try {
            val html = context.assets.open("web_app_index.html").bufferedReader().use { it.readText() }
            sendResponse(socket, 200, html, "text/html")
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error reading web_app_index.html", e)
            sendResponse(socket, 500, "Error loading web-app: ${e.localizedMessage}", "text/plain")
        }
    }
"""

# We replace slice from index 413 to 1311
lines[413:1311] = [replacement + '\n']
print('Length after:', len(lines))

with open('app/src/main/java/com/example/webserver/AdminWebServer.kt', 'w') as f:
    f.writelines(lines)
