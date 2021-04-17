# server-restarter

How to use:
1. Download mod.
2. Place in mods folder.
3. Create a file called `config/server_restarter.json` and fill out this template: (temporary solution until I implement [#1](https://github.com/Geek202/server-restarter/issues/1))
```json
{
  "webhook_url": "",
  "scheduled_actions": []
}
```
5. Profit

#### `scheduled_actions` config
Allows scheduling restarts/stops of the server using cron timings.
Example: (note: not valid JSON, remove the comments)
```json
{
  "webhook_url": "<your url>",
  "scheduled_actions": [
    {
      "action": "Restart", // or "Stop", requires capitalisation
      "cron": "0 0 * * *", // Cron specification 'min hour day month dow'
      "message": "Automatic daily restart" // Message used for reason of restart, only applies for "action": "Restart"
    }
  ]
}
```

NOTE: The server launch also needs to be wrapped with [server-launcher](https://github.com/Geek202/server-launcher)
