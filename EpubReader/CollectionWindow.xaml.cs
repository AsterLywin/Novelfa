using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using EpubReader.Models;
using EpubReader.Services;

namespace EpubReader
{

public partial class CollectionWindow : Window
{
	private string collectionName;

	private List<Book> books = new List<Book>();

	public CollectionWindow(string collectionName)
	{
		InitializeComponent();
		Loc.Prepare(this);
		WindowMax.Attach(this, null);
		this.collectionName = collectionName;
		LoadBooks();
	}

	private void LoadBooks()
	{
		books = LibraryService.LoadBooks()
			.Where((Book b) => b.CollectionName == collectionName)
			.Where((Book b) => !b.IsPrivate || LibraryWindow.PrivateUnlockedThisSession)
			.OrderBy((Book b) => b.DisplayTitle, NaturalStringComparer.Instance)
			.ToList();
		List<CollectionBookItem> items = new List<CollectionBookItem>();
		for (int i = 0; i < books.Count; i++)
		{
			items.Add(new CollectionBookItem
			{
				Book = books[i],
				VolumeText = Loc.F("جلد {0}", i + 1)
			});
		}
		listBoxBooks.ItemsSource = null;
		listBoxBooks.ItemsSource = items;
		txtCollectionTitle.Text = collectionName;
		txtCollectionTitle.FlowDirection = Loc.Flow;
		Loc.FixTextDirection(txtCollectionTitle);
		Title = collectionName;
		int done = books.Count((Book b) => b.IsCompleted);
		int totalChapters = books.Sum((Book b) => b.TotalChapters);
		txtCollectionStats.Text = Loc.F("{0} جلد  ·  {1} فصل  ·  {2} جلد تموم شده", books.Count, totalChapters, done);
		txtCount.Text = "(" + books.Count + ")";
		progressCollection.Value = ((books.Count > 0) ? books.Average((Book b) => b.ProgressPercent) : 0.0);
		txtEmpty.Visibility = ((books.Count != 0) ? Visibility.Collapsed : Visibility.Visible);
		btnContinue.IsEnabled = books.Count > 0;
		Book coverBook = books.FirstOrDefault((Book b) => !string.IsNullOrEmpty(b.CoverImagePath) && File.Exists(b.CoverImagePath));
		ImageSource cover = ((coverBook != null) ? LoadImage(coverBook.CoverImagePath) : null);
		imgHeroCover.Source = cover;
		imgHeroBackdrop.Source = cover;
	}

	private static ImageSource LoadImage(string path)
	{
		try
		{
			BitmapImage bmp = new BitmapImage();
			bmp.BeginInit();
			bmp.CacheOption = BitmapCacheOption.OnLoad;
			bmp.UriSource = new Uri(path, UriKind.Absolute);
			bmp.EndInit();
			bmp.Freeze();
			return bmp;
		}
		catch
		{
			return null;
		}
	}

	private Book GetBookToContinue()
	{
		Book inProgress = (from b in books
			where b.IsInProgress
			orderby b.LastOpenedDate descending
			select b).FirstOrDefault();
		if (inProgress != null)
		{
			return inProgress;
		}
		return books.FirstOrDefault((Book b) => !b.IsCompleted) ?? books.FirstOrDefault();
	}

	private void btnContinue_Click(object sender, RoutedEventArgs e)
	{
		Book book = GetBookToContinue();
		if (book != null)
		{
			OpenBook(book);
		}
	}

	private void btnRename_Click(object sender, RoutedEventArgs e)
	{
		string newName = DarkMessageBox.Prompt("اسم جدید مجموعه:", "تغییر نام مجموعه", collectionName);
		if (!string.IsNullOrWhiteSpace(newName))
		{
			newName = newName.Trim();
			if (!(newName == collectionName))
			{
				LibraryService.RenameCollection(collectionName, newName);
				collectionName = newName;
				LoadBooks();
			}
		}
	}

	private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
	{
		if (e.ClickCount == 2)
		{
			WindowMax.Toggle(this);
			return;
		}
		if (WindowState == WindowState.Maximized)
		{
			return;
		}
		DragMove();
	}

	private void btnMaximize_Click(object sender, RoutedEventArgs e)
	{
		WindowMax.Toggle(this);
	}

	private void btnMinimize_Click(object sender, RoutedEventArgs e)
	{
		WindowState = WindowState.Minimized;
	}

	private void btnClose_Click(object sender, RoutedEventArgs e)
	{
		Close();
	}

	private void btnBack_Click(object sender, RoutedEventArgs e)
	{
		Close();
	}

	private void BookCard_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
	{
		FrameworkElement fe = sender as FrameworkElement;
		CollectionBookItem item = (fe != null) ? (fe.Tag as CollectionBookItem) : null;
		if (item != null)
		{
			if (LibraryWindow.AskRestart(item.Book))
			{
				OpenBook(item.Book);
			}
		}
	}

	private void OpenBook(Book selectedBook)
	{
		if (!File.Exists(selectedBook.FilePath))
		{
			DarkMessageBox.Show("فایل کتاب پیدا نشد. ممکنه جابجا یا حذف شده باشه.\nاین کتاب از کتابخانه حذف می‌شه.", "خطا", MessageBoxButton.OK, MessageBoxImage.Exclamation);
			LibraryService.RemoveBook(selectedBook.FilePath);
			LoadBooks();
			return;
		}
		LibraryService.LogOpen("[Collection] request: " + selectedBook.DisplayTitle + " | " + selectedBook.FilePath);
		LibraryService.MarkAsOpened(selectedBook.FilePath);
		selectedBook.LastOpenedDate = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
		MainWindow readerWindow = new MainWindow();
		if (!readerWindow.OpenBook(selectedBook))
		{
			readerWindow.Close();
			return;
		}
		readerWindow.Closed += (object s, EventArgs args) =>
		{
			LoadBooks();
			Show();
			Activate();
		};
		readerWindow.Show();
		Hide();
	}

	private void BtnMore_Click(object sender, RoutedEventArgs e)
	{
		Button button = sender as Button;
		CollectionBookItem item = button?.Tag as CollectionBookItem;
		if (item == null)
		{
			return;
		}
		ContextMenu contextMenu = new ContextMenu();
		contextMenu.FlowDirection = Loc.Flow;
		ContextMenu menu = contextMenu;
		MenuItem menuItem = new MenuItem();
		menuItem.Header = Loc.T("باز کردن");
		MenuItem openItem = menuItem;
		openItem.Click += (object s, RoutedEventArgs a) =>
		{
			if (LibraryWindow.AskRestart(item.Book))
			{
				OpenBook(item.Book);
			}
		};
		menu.Items.Add(openItem);
		menuItem = new MenuItem();
		menuItem.Header = Loc.T("انتقال به مجموعه دیگه");
		MenuItem moveItem = menuItem;
		moveItem.Click += (object s, RoutedEventArgs a) =>
		{
			string text = CollectionPickerWindow.PickCollection(LibraryService.GetAllCollectionNames());
			if (text != null)
			{
				LibraryService.SetCollection(item.Book.FilePath, text);
				LoadBooks();
			}
		};
		menu.Items.Add(moveItem);
		menuItem = new MenuItem();
		menuItem.Header = Loc.T("حذف از مجموعه");
		menuItem.Foreground = (Brush)FindResource("DangerColor");
		MenuItem removeItem = menuItem;
		removeItem.Click += (object s, RoutedEventArgs a) =>
		{
			LibraryService.RemoveFromCollection(item.Book.FilePath);
			LoadBooks();
			if (books.Count == 0)
			{
				Close();
			}
		};
		menu.Items.Add(removeItem);
		menu.PlacementTarget = button;
		menu.IsOpen = true;
		e.Handled = true;
	}
}
}
