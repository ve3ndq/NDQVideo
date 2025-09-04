# IPTV Configuration Setup

## Secure Configuration

This app uses a secure configuration file to store IPTV server URLs and credentials. The configuration file is **not committed to GitHub** for security reasons.

## Setup Instructions

1. **Copy the template file:**
   ```bash
   cp app/src/main/assets/config.template.json app/src/main/assets/config.json
   ```

2. **Edit the configuration file:**
   Open `app/src/main/assets/config.json` and replace the placeholder values with your actual IPTV server details:

   ```json
   {
     "m3u_url": "http://your-iptv-server.com/get.php?username=YOUR_USERNAME&password=YOUR_PASSWORD&type=m3u&output=mpegts",
     "epg_url": "http://your-iptv-server.com/epg.php?username=YOUR_USERNAME&password=YOUR_PASSWORD"
   }
   ```

3. **Security Note:**
   - The `config.json` file is automatically excluded from Git commits via `.gitignore`
   - Never commit your actual credentials to version control
   - Share only the `config.template.json` file as a reference

## Fallback Behavior

If the `config.json` file is missing or cannot be read, the app will use fallback URLs for development/testing purposes. However, for production use, always provide a valid `config.json` file.

## File Structure

```
app/src/main/assets/
├── config.json          # Your actual configuration (not in Git)
└── config.template.json # Template file (safe to commit)
```