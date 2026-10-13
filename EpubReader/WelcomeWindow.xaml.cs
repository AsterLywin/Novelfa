using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using EpubReader.Services;

namespace EpubReader
{
    public partial class WelcomeWindow : Window
    {
        // مدت نمایش پیام خوش‌آمد قبل از بسته‌شدن خودکار (ثانیه)
        private const double AutoCloseSeconds = 2.5;

        private DispatcherTimer closeTimer;

        // true یعنی کاربر می‌خواهد اسمش را عوض کند (نه اولین اجرا)
        private readonly bool editMode;

        // فقط وقتی کاربر «خروج از برنامه» را بزند true می‌شود
        private bool allowClose;

        // اسم واردشده (بعد از ذخیره)
        public string EnteredName { get; private set; }

        public WelcomeWindow()
            : this(false, null)
        {
        }

        public WelcomeWindow(bool editMode, string currentName)
        {
            InitializeComponent();
            Loc.Prepare(this);
            this.editMode = editMode;
            txtName.MaxLength = ProfileService.MaxNameLength;
            Closed += (s, e) => StopTimer();

            if (editMode)
            {
                txtTitle.Text = Loc.T("تغییر اسم");
                txtSubtitle.Text = Loc.T("اسم جدیدت رو بنویس");
                btnContinue.Content = Loc.T("ذخیره");
                txtName.Text = currentName ?? "";
                btnCancel.Visibility = Visibility.Visible;
                btnCancel.IsCancel = true;   // انصراف و کلید Esc پنجره را می‌بندند
            }
            else
            {
                // اولین اجرا: کارت را نمی‌شود بست، پس ضربدر کل برنامه را می‌بندد
                btnClose.Visibility = Visibility.Visible;
            }
        }

        // اولین اجرا: تا وقتی اسم ذخیره نشده، پنجره بسته نمی‌شود (Alt+F4 هم کار نمی‌کند)
        protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
        {
            if (!allowClose && !editMode && !ProfileService.HasProfile)
            {
                e.Cancel = true;
                txtError.Text = Loc.T("برای ادامه، اول یه اسم وارد کن");
                txtError.Visibility = Visibility.Visible;
                txtName.Focus();
                return;
            }
            base.OnClosing(e);
        }

        private void Window_Loaded(object sender, RoutedEventArgs e)
        {
            txtName.Focus();
            if (editMode) txtName.SelectAll();
        }

        private void Header_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ButtonState == MouseButtonState.Pressed) DragMove();
        }

        private void txtName_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) Submit();
        }

        private void txtName_TextChanged(object sender, TextChangedEventArgs e)
        {
            txtError.Visibility = Visibility.Collapsed;
        }

        private void btnClose_Click(object sender, RoutedEventArgs e)
        {
            allowClose = true;
            Application.Current.Shutdown();
        }

        private void btnContinue_Click(object sender, RoutedEventArgs e)
        {
            Submit();
        }

        private void Submit()
        {
            string name = ProfileService.CleanName(txtName.Text);
            if (name.Length == 0)
            {
                txtError.Text = Loc.T("لطفاً اسمت رو وارد کن");
                txtError.Visibility = Visibility.Visible;
                txtName.Focus();
                return;
            }

            // اسم همین‌جا ذخیره می‌شود تا اگر کاربر پیام خوش‌آمد را بست، اسم از دست نرود
            ProfileService.SaveName(name);
            EnteredName = name;

            if (editMode)
            {
                // در حالت تغییر اسم، پیام خوش‌آمد لازم نیست
                DialogResult = true;
                return;
            }

            ShowWelcome(name);
        }

        private void ShowWelcome(string name)
        {
            panelAsk.Visibility = Visibility.Collapsed;
            btnClose.Visibility = Visibility.Collapsed;   // اسم ذخیره شده؛ دیگر ضربدر لازم نیست
            panelWelcome.Visibility = Visibility.Visible;

            txtTitle.Text = Loc.T("به ناول\u200cفا ریدر خوش اومدی");
            txtSubtitle.Visibility = Visibility.Collapsed;
            txtTitle.Margin = new Thickness(0, 18, 0, 18);
            txtWelcomeName.Text = name;

            btnStart.Focus();

            // بسته‌شدن خودکار بعد از چند ثانیه
            closeTimer = new DispatcherTimer();
            closeTimer.Interval = TimeSpan.FromSeconds(AutoCloseSeconds);
            closeTimer.Tick += (s, e) => FinishWelcome();
            closeTimer.Start();
        }

        private void btnStart_Click(object sender, RoutedEventArgs e)
        {
            FinishWelcome();
        }

        private void FinishWelcome()
        {
            StopTimer();
            DialogResult = true;
        }

        private void StopTimer()
        {
            if (closeTimer != null)
            {
                closeTimer.Stop();
                closeTimer = null;
            }
        }
    }
}
