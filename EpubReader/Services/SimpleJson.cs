using System.Collections.Generic;
using System.Text;
using EpubReader.Models;

namespace EpubReader.Services
{

public static class SimpleJson
{
	public static string SerializeBooks(List<Book> books)
	{
		StringBuilder sb = new StringBuilder();
		sb.Append("[");
		for (int i = 0; i < books.Count; i++)
		{
			Book b = books[i];
			sb.Append("{");
			sb.Append("\"Id\":\"" + Escape(b.Id) + "\",");
			sb.Append("\"Title\":\"" + Escape(b.Title) + "\",");
			sb.Append("\"FilePath\":\"" + Escape(b.FilePath) + "\",");
			sb.Append("\"CoverImagePath\":\"" + Escape(b.CoverImagePath) + "\",");
			sb.Append("\"TotalChapters\":" + b.TotalChapters + ",");
			sb.Append("\"LastChapterIndex\":" + b.LastChapterIndex + ",");
			sb.Append("\"DateAdded\":\"" + Escape(b.DateAdded) + "\",");
			sb.Append("\"LastOpenedDate\":\"" + Escape(b.LastOpenedDate) + "\",");
			sb.Append("\"IsPrivate\":" + (b.IsPrivate ? "true" : "false") + ",");
			sb.Append("\"IsFavorite\":" + (b.IsFavorite ? "true" : "false") + ",");
			sb.Append("\"CollectionName\":\"" + Escape(b.CollectionName) + "\",");
			sb.Append("\"FontFamily\":\"" + Escape(b.FontFamily) + "\",");
			sb.Append("\"FontSize\":" + b.FontSize + ",");
			sb.Append("\"IsDarkMode\":" + (b.IsDarkMode ? "true" : "false") + ",");
			sb.Append("\"Theme\":\"" + Escape(b.Theme) + "\",");
			sb.Append("\"LineHeight\":" + b.LineHeight.ToString("0.0", System.Globalization.CultureInfo.InvariantCulture) + ",");
			sb.Append("\"SideMargin\":" + b.SideMargin + ",");
			sb.Append("\"ParagraphSpacing\":" + b.ParagraphSpacing.ToString("0.0", System.Globalization.CultureInfo.InvariantCulture));
			sb.Append("}");
			if (i < books.Count - 1)
			{
				sb.Append(",");
			}
		}
		sb.Append("]");
		return sb.ToString();
	}

	public static List<Book> DeserializeBooks(string json)
	{
		List<Book> books = new List<Book>();
		if (string.IsNullOrWhiteSpace(json) || json.Trim() == "[]")
		{
			return books;
		}
		int depth = 0;
		int startIndex = -1;
		bool inString = false;
		for (int i = 0; i < json.Length; i++)
		{
			char c = json[i];
			if (inString)
			{
				if (c == '\\')
				{
					i++;
				}
				else if (c == '"')
				{
					inString = false;
				}
				continue;
			}
			if (c == '"')
			{
				inString = true;
			}
			else if (c == '{')
			{
				if (depth == 0)
				{
					startIndex = i;
				}
				depth++;
			}
			else if (c == '}')
			{
				depth--;
				if (depth == 0 && startIndex >= 0)
				{
					string objJson = json.Substring(startIndex, i - startIndex + 1);
					books.Add(ParseBook(objJson));
				}
			}
		}
		return books;
	}

	private static Book ParseBook(string objJson)
	{
		Book book = new Book();
		book.Id = ExtractStringValue(objJson, "Id");
		book.Title = ExtractStringValue(objJson, "Title");
		book.FilePath = ExtractStringValue(objJson, "FilePath");
		book.CoverImagePath = ExtractStringValue(objJson, "CoverImagePath");
		book.DateAdded = ExtractStringValue(objJson, "DateAdded");
		book.LastOpenedDate = ExtractStringValue(objJson, "LastOpenedDate");
		book.CollectionName = ExtractStringValue(objJson, "CollectionName");
		book.FontFamily = ExtractStringValue(objJson, "FontFamily");
		book.Theme = ExtractStringValue(objJson, "Theme");
		string totalStr = ExtractRawValue(objJson, "TotalChapters");
		int total;
		book.TotalChapters = (int.TryParse(totalStr, out total) ? total : 0);
		string lastStr = ExtractRawValue(objJson, "LastChapterIndex");
		int last;
		book.LastChapterIndex = (int.TryParse(lastStr, out last) ? last : 0);
		string privateStr = ExtractRawValue(objJson, "IsPrivate");
		bool isPrivate;
		bool.TryParse(privateStr, out isPrivate);
		book.IsPrivate = isPrivate;
		bool isFavorite;
		bool.TryParse(ExtractRawValue(objJson, "IsFavorite"), out isFavorite);
		book.IsFavorite = isFavorite;
		string fontSizeStr = ExtractRawValue(objJson, "FontSize");
		int fontSize;
		book.FontSize = ((int.TryParse(fontSizeStr, out fontSize) && fontSize > 0) ? fontSize : 18);
		string darkModeStr = ExtractRawValue(objJson, "IsDarkMode");
		bool isDarkMode;
		bool.TryParse(darkModeStr, out isDarkMode);
		book.IsDarkMode = isDarkMode;
		double lineHeightValue;
		book.LineHeight = ((double.TryParse(ExtractRawValue(objJson, "LineHeight"), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out lineHeightValue) && lineHeightValue > 0.0) ? lineHeightValue : 0.0);
		int sideMarginValue;
		book.SideMargin = ((int.TryParse(ExtractRawValue(objJson, "SideMargin"), out sideMarginValue) && sideMarginValue >= 0) ? sideMarginValue : 0);
		double paragraphValue;
		book.ParagraphSpacing = ((double.TryParse(ExtractRawValue(objJson, "ParagraphSpacing"), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out paragraphValue) && paragraphValue >= 0.0) ? paragraphValue : 0.0);
		if (string.IsNullOrEmpty(book.FontFamily))
		{
			book.FontFamily = "Tahoma";
		}
		return book;
	}

	private static string ExtractStringValue(string json, string key)
	{
		string pattern = "\"" + key + "\":\"";
		int start = json.IndexOf(pattern);
		if (start < 0)
		{
			return "";
		}
		start += pattern.Length;
		int end = start;
		while (end < json.Length)
		{
			if (json[end] == '\\')
			{
				end += 2;
				continue;
			}
			if (json[end] == '"')
			{
				break;
			}
			end++;
		}
		string raw = json.Substring(start, end - start);
		return Unescape(raw);
	}

	private static string ExtractRawValue(string json, string key)
	{
		string pattern = "\"" + key + "\":";
		int start = json.IndexOf(pattern);
		if (start < 0)
		{
			return "0";
		}
		start += pattern.Length;
		int end;
		for (end = start; end < json.Length && json[end] != ',' && json[end] != '}'; end++)
		{
		}
		return json.Substring(start, end - start).Trim();
	}

	private static string Escape(string value)
	{
		if (string.IsNullOrEmpty(value))
		{
			return "";
		}
		return value.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "")
			.Replace("\n", "\\n");
	}

	private static string Unescape(string value)
	{
		if (string.IsNullOrEmpty(value))
		{
			return "";
		}
		StringBuilder sb = new StringBuilder(value.Length);
		for (int i = 0; i < value.Length; i++)
		{
			if (value[i] == '\\' && i + 1 < value.Length)
			{
				int num = i + 1;
				i = num;
				char next = value[num];
				sb.Append((next == 'n') ? '\n' : next);
			}
			else
			{
				sb.Append(value[i]);
			}
		}
		return sb.ToString();
	}
}
}
