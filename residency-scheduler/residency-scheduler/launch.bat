@echo off
echo Launching Residency Rotation Scheduler...
echo Output will be saved to launch.log
mvn javafx:run -DskipTests > launch.log 2>&1
echo.
echo Launch attempt finished. Check launch.log for details.
pause