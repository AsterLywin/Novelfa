using System;
using System.Collections.Generic;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace EpubReader.Models
{

public class LibraryCardItem
{
	public bool IsCollection { get; set; }

	public string Title { get; set; }

	public string CoverImagePath { get; set; }

	// جلد کوچک‌شده برای نمایش روی کارت (به‌جای دیکد کردن عکس کامل). یک‌بار ساخته و نگه‌داری می‌شه
	private static readonly Dictionary<string, ImageSource> coverCache = new Dictionary<string, ImageSource>(StringComparer.OrdinalIgnoreCase);

	private const int CoverDecodeWidth = 360;

	public ImageSource CoverImage
	{
		get
		{
			string path = CoverImagePath;
			if (string.IsNullOrEmpty(path))
			{
				return null;
			}
			ImageSource cached;
			if (coverCache.TryGetValue(path, out cached))
			{
				return cached;
			}
			ImageSource loaded = LoadCover(path);
			if (loaded != null)
			{
				coverCache[path] = loaded;
			}
			return loaded;
		}
	}

	private static ImageSource LoadCover(string path)
	{
		try
		{
			if (!File.Exists(path))
			{
				return null;
			}
			BitmapImage bmp = new BitmapImage();
			bmp.BeginInit();
			bmp.CacheOption = BitmapCacheOption.OnLoad;
			bmp.DecodePixelWidth = CoverDecodeWidth;
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

	public string SubtitleText { get; set; }

	public string ProgressText { get; set; }

	public double ProgressPercent { get; set; }

	public Book SingleBook { get; set; }

	public string CollectionName { get; set; }

	public List<Book> CollectionBooks { get; set; }

	public Visibility CollectionBadgeVisibility => (!IsCollection) ? Visibility.Collapsed : Visibility.Visible;

	public bool IsCompleted { get; set; }

	public bool IsFavorite { get; set; }

	public Visibility FavoriteVisibility => IsFavorite ? Visibility.Visible : Visibility.Collapsed;

	public string PercentText { get; set; }

	public Visibility ProgressVisibility => string.IsNullOrEmpty(ProgressText) ? Visibility.Collapsed : Visibility.Visible;
}
}
