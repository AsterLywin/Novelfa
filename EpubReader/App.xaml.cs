using System;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Threading;
using Microsoft.Win32;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using EpubReader.Services;

namespace EpubReader
{
    public partial class App : Application
    {
        private static bool showingError = false;

        // فایل EPUB ای که از بیرون (دابل‌کلیک / Open with در ویندوز) به برنامه داده شده
        public static string StartupFile;

        protected override void OnStartup(StartupEventArgs e)
        {
            // گیرنده‌های خطای سراسری: قبل از هر کار دیگه‌ای ثبت می‌شن
            DispatcherUnhandledException += OnDispatcherUnhandledException;
            AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;
            TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;

            foreach (string arg in e.Args)
            {
                try
                {
                    string full = Path.GetFullPath(arg.Trim('"'));
                    if (File.Exists(full) && full.EndsWith(".epub", StringComparison.OrdinalIgnoreCase))
                    {
                        StartupFile = full;
                        break;
                    }
                }
                catch { }
            }

            // فقط یک نمونه از برنامه اجرا می‌شه؛ اگه از قبل بازه، فایل به همون فرستاده می‌شه و این نمونه بسته می‌شه
            if (!AcquireSingleInstance())
            {
                Shutdown(0);
                return;
            }
            StartPipeServer();
            RegisterFileAssociation();

            AppSettings.Load();
            Loc.Init();
            ThemeManager.Apply(AppSettings.Theme);
            CustomFontService.Initialize();
            try { RecommendedFontService.Initialize(); } catch { }
            base.OnStartup(e);
        }

        // ---------- تک‌نمونه‌ای (Single Instance) ----------

        private static Mutex instanceMutex;

        private static string PipeName
        {
            get { return "EpubReaderApp_Pipe_" + Environment.UserName; }
        }

        // true = این نمونه‌ی اصلیه و ادامه می‌ده، false = فایل به نمونه‌ی در حال اجرا فرستاده شد و باید بسته بشه
        private static bool AcquireSingleInstance()
        {
            bool created;
            try
            {
                instanceMutex = new Mutex(true, @"Local\EpubReaderApp_SingleInstance", out created);
            }
            catch
            {
                return true;
            }
            if (created)
            {
                return true;
            }

            string message = StartupFile ?? "";
            for (int i = 0; i < 12; i++)
            {
                try
                {
                    using (NamedPipeClientStream client = new NamedPipeClientStream(".", PipeName, PipeDirection.Out))
                    {
                        client.Connect(1000);
                        byte[] data = Encoding.UTF8.GetBytes(message);
                        client.Write(data, 0, data.Length);
                        client.Flush();
                    }
                    return false;
                }
                catch
                {
                    Thread.Sleep(300);
                }
            }
            // به نمونه‌ی اول نرسیدیم (مثلاً هنگ کرده): بهتره کاربر فایلش رو از دست نده، پس عادی ادامه بده
            return true;
        }

        private void StartPipeServer()
        {
            Thread thread = new Thread(() =>
            {
                while (true)
                {
                    try
                    {
                        using (NamedPipeServerStream server = new NamedPipeServerStream(PipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.None))
                        {
                            server.WaitForConnection();
                            string message;
                            using (StreamReader reader = new StreamReader(server, Encoding.UTF8))
                            {
                                message = reader.ReadToEnd();
                            }
                            Dispatcher.BeginInvoke(new Action(() => HandleExternalOpen(message)));
                        }
                    }
                    catch
                    {
                        Thread.Sleep(500);
                    }
                }
            });
            thread.IsBackground = true;
            thread.Start();
        }

        // نمونه‌ی دوم یه فایل فرستاده: خوانش/مجموعه‌ی باز رو می‌بندیم و همون فایل رو باز می‌کنیم
        private void HandleExternalOpen(string message)
        {
            try
            {
                string path = null;
                if (!string.IsNullOrWhiteSpace(message))
                {
                    try
                    {
                        string full = Path.GetFullPath(message.Trim().Trim('"'));
                        if (File.Exists(full) && full.EndsWith(".epub", StringComparison.OrdinalIgnoreCase))
                        {
                            path = full;
                        }
                    }
                    catch
                    {
                    }
                }

                LibraryWindow library = Windows.OfType<LibraryWindow>().FirstOrDefault();
                if (library == null)
                {
                    return;
                }

                if (path == null)
                {
                    // فقط بیار جلو
                    Window front = Windows.OfType<Window>().FirstOrDefault((Window w) => (w is MainWindow || w is CollectionWindow) && w.IsVisible) ?? library;
                    if (front.WindowState == WindowState.Minimized)
                    {
                        front.WindowState = WindowState.Normal;
                    }
                    front.Activate();
                    return;
                }

                foreach (Window w in Windows.OfType<Window>().Where((Window x) => x is MainWindow || x is CollectionWindow).ToList())
                {
                    try
                    {
                        w.Close();
                    }
                    catch
                    {
                    }
                }
                if (library.WindowState == WindowState.Minimized)
                {
                    library.WindowState = WindowState.Normal;
                }
                library.OpenExternalFile(path);
            }
            catch (Exception ex)
            {
                WriteLog("External", ex);
            }
        }

        // برنامه رو توی لیست «Open with» برای .epub ثبت می‌کنه (فقط برای کاربر فعلی، بدون نیاز به Admin)
        private static void RegisterFileAssociation()
        {
            try
            {
                string exe = System.Reflection.Assembly.GetExecutingAssembly().Location;
                string command = "\"" + exe + "\" \"%1\"";
                string progId = "EpubReaderApp.epub";
                string commandKeyPath = @"Software\Classes\" + progId + @"\shell\open\command";

                using (RegistryKey existing = Registry.CurrentUser.OpenSubKey(commandKeyPath))
                {
                    if (existing != null && string.Equals(existing.GetValue("") as string, command, StringComparison.OrdinalIgnoreCase))
                    {
                        return;
                    }
                }
                using (RegistryKey k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\" + progId))
                {
                    k.SetValue("", "EPUB Book");
                }
                using (RegistryKey k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\" + progId + @"\DefaultIcon"))
                {
                    k.SetValue("", "\"" + exe + "\",0");
                }
                using (RegistryKey k = Registry.CurrentUser.CreateSubKey(commandKeyPath))
                {
                    k.SetValue("", command);
                }
                using (RegistryKey k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\.epub\OpenWithProgids"))
                {
                    k.SetValue(progId, new byte[0], RegistryValueKind.None);
                }
            }
            catch
            {
            }
        }

        // خطای پیش‌بینی‌نشده توی رابط کاربری: ثبت می‌شه و برنامه ادامه می‌ده
        private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
        {
            WriteLog("UI", e.Exception);
            ShowFriendlyError();
            e.Handled = true;
        }

        // خطای بیرون از رابط کاربری: فقط ثبت می‌شه (ویندوز برنامه رو می‌بنده)
        private void OnDomainUnhandledException(object sender, UnhandledExceptionEventArgs e)
        {
            WriteLog("Domain", e.ExceptionObject as Exception);
        }

        private void OnUnobservedTaskException(object sender, UnobservedTaskExceptionEventArgs e)
        {
            WriteLog("Task", e.Exception);
            e.SetObserved();
        }

        private static string LogPath
        {
            get
            {
                string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EpubReaderApp");
                if (!Directory.Exists(folder))
                {
                    Directory.CreateDirectory(folder);
                }
                return Path.Combine(folder, "error.log");
            }
        }

        private static void WriteLog(string source, Exception ex)
        {
            try
            {
                string path = LogPath;

                // فایل لاگ بزرگ نشه
                FileInfo fi = new FileInfo(path);
                if (fi.Exists && fi.Length > 512 * 1024)
                {
                    File.Delete(path);
                }

                StringBuilder sb = new StringBuilder();
                sb.AppendLine("==== " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " [" + source + "]");
                sb.AppendLine("OS: " + Environment.OSVersion + " | .NET: " + Environment.Version);
                sb.AppendLine(ex != null ? ex.ToString() : "(no exception object)");
                sb.AppendLine();
                File.AppendAllText(path, sb.ToString(), Encoding.UTF8);
            }
            catch
            {
                // نوشتن لاگ نباید خودش باعث خطا بشه
            }
        }

        private static void ShowFriendlyError()
        {
            if (showingError) return;
            showingError = true;
            try
            {
                MessageBox.Show(
                    Loc.T("یک خطای غیرمنتظره رخ داد، ولی برنامه ادامه می‌ده.\n\nجزئیات توی این فایل ذخیره شد:\n") + LogPath,
                    Loc.T("خطا"),
                    MessageBoxButton.OK,
                    MessageBoxImage.Warning,
                    MessageBoxResult.OK,
                    MessageBoxOptions.RtlReading | MessageBoxOptions.RightAlign);
            }
            catch
            {
            }
            finally
            {
                showingError = false;
            }
        }
    }
}
