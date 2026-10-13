using System;

namespace EpubReader.Models
{

public class Book
{
	public string Id { get; set; }

	public string Title { get; set; }

	// اسمی که توی برنامه نشون داده می‌شه (ذخیره نمی‌شه). اگه چند کتاب اسم یکسان داشتن، اسم فایل نشون داده می‌شه
	public string DisplayTitle { get { return string.IsNullOrEmpty(displayTitle) ? Title : displayTitle; } set { displayTitle = value; } }

	private string displayTitle;

	public string FilePath { get; set; }

	public string CoverImagePath { get; set; }

	public int TotalChapters { get; set; }

	public int LastChapterIndex { get; set; }

	public int LastChapterDisplay => LastChapterIndex + 1;

	public string FontFamily { get; set; }

	public int FontSize { get; set; }

	public bool IsDarkMode { get; set; } = true;

	public string Theme { get; set; }

	// فاصله‌های متن (برای هر کتاب جدا). LineHeight صفر یعنی هنوز برای این کتاب ذخیره نشده
	public double LineHeight { get; set; }

	public int SideMargin { get; set; }

	public double ParagraphSpacing { get; set; }

	public string DateAdded { get; set; }

	public string LastOpenedDate { get; set; }

	public bool IsPrivate { get; set; }

	public bool IsFavorite { get; set; }

	public string CollectionName { get; set; }

	public bool IsCompleted => TotalChapters > 0 && LastChapterIndex + 1 >= TotalChapters && !string.IsNullOrEmpty(LastOpenedDate);

	public bool IsInProgress => !string.IsNullOrEmpty(LastOpenedDate) && !IsCompleted;

	public int PercentDisplay => IsCompleted ? 100 : (int)Math.Min(99.0, Math.Floor(ProgressPercent));

	public double ProgressPercent
	{
		get
		{
			if (TotalChapters <= 0 || string.IsNullOrEmpty(LastOpenedDate))
			{
				return 0.0;
			}
			return Math.Min(100.0, (double)(LastChapterIndex + 1) * 100.0 / (double)TotalChapters);
		}
	}
}
}
