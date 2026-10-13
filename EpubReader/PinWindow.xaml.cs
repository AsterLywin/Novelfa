using System.Linq;
using System.Windows;
using System.Windows.Input;
using System.Windows.Markup;
using EpubReader.Services;

namespace EpubReader
{

public partial class PinWindow : Window
{
	private readonly bool isSetupMode;

	private readonly bool isChangeMode;

	public PinWindow()
		: this(forceSetupMode: false)
	{
	}

	public PinWindow(bool forceSetupMode)
	{
		InitializeComponent();
		Loc.Prepare(this);
		bool hasPin = SettingsService.HasPin();
		isChangeMode = forceSetupMode & hasPin;
		isSetupMode = forceSetupMode || !hasPin;
		if (isSetupMode)
		{
			txtTitle.Text = (isChangeMode ? "پین جدید رو وارد کنید" : "یک پین بسازید");
			txtSubtitle.Text = (isChangeMode ? "پین جدید جایگزین پین قبلی میشه" : "برای قفل کردن کتاب\u200cهای خصوصی");
			lblPin.Text = (isChangeMode ? "پین جدید" : "پین");
			lblConfirm.Visibility = Visibility.Visible;
			pinBox2.Visibility = Visibility.Visible;
		}
		else
		{
			txtTitle.Text = "پین رو وارد کنید";
			txtSubtitle.Text = "بخش خصوصی قفله";
			lblConfirm.Visibility = Visibility.Collapsed;
			pinBox2.Visibility = Visibility.Collapsed;
		}
		Window owner = Application.Current.Windows.OfType<Window>().FirstOrDefault((Window w) => w.IsActive && w != this);
		if (owner != null)
		{
			Owner = owner;
		}
		else
		{
			WindowStartupLocation = WindowStartupLocation.CenterScreen;
		}
	}

	private void Window_Loaded(object sender, RoutedEventArgs e)
	{
		pinBox1.Focus();
	}

	private void ShowError(string message)
	{
		txtError.Text = Loc.T(message);
		txtError.Visibility = Visibility.Visible;
	}

	private void btnOk_Click(object sender, RoutedEventArgs e)
	{
		string pin = pinBox1.Password;
		if (string.IsNullOrEmpty(pin))
		{
			ShowError("پین نمی\u200cتونه خالی باشه");
		}
		else if (isSetupMode)
		{
			if (pin.Length < 4)
			{
				ShowError("پین باید حداقل ۴ کاراکتر باشه");
			}
			else if (pin != pinBox2.Password)
			{
				ShowError("پین\u200cها یکسان نیستن");
				pinBox2.Clear();
				pinBox2.Focus();
			}
			else
			{
				SettingsService.SetPin(pin);
				DialogResult = true;
			}
		}
		else if (SettingsService.VerifyPin(pin))
		{
			DialogResult = true;
		}
		else
		{
			ShowError("پین اشتباهه");
			pinBox1.Clear();
			pinBox1.Focus();
		}
	}

	private void btnCancel_Click(object sender, RoutedEventArgs e)
	{
		DialogResult = false;
	}

	private void Border_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
	{
		try
		{
			DragMove();
		}
		catch
		{
		}
	}

	private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
	{
		if (e.Key == Key.Return)
		{
			if (isSetupMode && pinBox1.IsKeyboardFocused)
			{
				pinBox2.Focus();
			}
			else
			{
				btnOk_Click(sender, e);
			}
			e.Handled = true;
		}
		else if (e.Key == Key.Escape)
		{
			DialogResult = false;
			e.Handled = true;
		}
	}

	public static bool Authenticate()
	{
		PinWindow win = new PinWindow();
		return win.ShowDialog() == true;
	}

	public static bool ChangePin()
	{
		if (SettingsService.HasPin() && !Authenticate())
		{
			return false;
		}
		PinWindow win = new PinWindow(forceSetupMode: true);
		return win.ShowDialog() == true;
	}
}
}
