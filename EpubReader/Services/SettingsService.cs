using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace EpubReader.Services
{

public static class SettingsService
{
	private static string SettingsFilePath
	{
		get
		{
			string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EpubReaderApp");
			if (!Directory.Exists(folder))
			{
				Directory.CreateDirectory(folder);
			}
			return Path.Combine(folder, "settings.txt");
		}
	}

	public static bool HasPin()
	{
		return File.Exists(SettingsFilePath) && !string.IsNullOrWhiteSpace(File.ReadAllText(SettingsFilePath, Encoding.UTF8));
	}

	public static void SetPin(string pin)
	{
		string hash = HashPin(pin);
		File.WriteAllText(SettingsFilePath, hash, Encoding.UTF8);
	}

	public static bool VerifyPin(string pin)
	{
		if (!HasPin())
		{
			return false;
		}
		string savedHash = File.ReadAllText(SettingsFilePath, Encoding.UTF8).Trim();
		string inputHash = HashPin(pin);
		return savedHash == inputHash;
	}

	private static string HashPin(string pin)
	{
		byte[] bytes;
		using (SHA256 sha = SHA256.Create())
		{
			bytes = sha.ComputeHash(Encoding.UTF8.GetBytes(pin));
		}
		StringBuilder sb = new StringBuilder();
		byte[] array = bytes;
		foreach (byte b in array)
		{
			sb.Append(b.ToString("x2"));
		}
		return sb.ToString();
	}
}
}
