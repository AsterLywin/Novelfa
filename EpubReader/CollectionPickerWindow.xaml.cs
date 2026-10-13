using System.Collections.Generic;
using System.Windows;
using EpubReader.Services;
using System.Windows.Input;
using System.Windows.Markup;

namespace EpubReader
{

public partial class CollectionPickerWindow : Window
{
	public string ChosenCollectionName { get; private set; }

	public CollectionPickerWindow(List<string> existingCollections)
	{
		InitializeComponent();
		Loc.Prepare(this);
		listExisting.ItemsSource = existingCollections;
	}

	private void btnOk_Click(object sender, RoutedEventArgs e)
	{
		string newName = ((txtNewName.Text != null) ? txtNewName.Text.Trim() : "");
		if (!string.IsNullOrEmpty(newName))
		{
			ChosenCollectionName = newName;
			DialogResult = true;
			Close();
		}
		else if (listExisting.SelectedItem != null)
		{
			ChosenCollectionName = listExisting.SelectedItem.ToString();
			DialogResult = true;
			Close();
		}
		else
		{
			DarkMessageBox.Show("یک مجموعه موجود را انتخاب کنید یا نام مجموعه جدید را بنویسید.", "خطا", MessageBoxButton.OK, MessageBoxImage.Exclamation);
		}
	}

	private void btnCancel_Click(object sender, RoutedEventArgs e)
	{
		DialogResult = false;
		Close();
	}

	private void Border_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
	{
		DragMove();
	}

	public static string PickCollection(List<string> existingCollections)
	{
		CollectionPickerWindow win = new CollectionPickerWindow(existingCollections);
		return (win.ShowDialog() == true) ? win.ChosenCollectionName : null;
	}
}
}
