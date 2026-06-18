# CPS-AirMouse

find program running on port 3001 with:
```bash
netstat -ano | findstr :3001

output: UDP    0.0.0.0:3001           *:*                                    30116
```

kill program with PID 30116 with:
```bash
taskkill /PID 30116 /F 
```