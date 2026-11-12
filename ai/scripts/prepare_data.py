"""Prepare the public benchmark datasets without changing the source files."""
from pathlib import Path
import hashlib
import json
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / 'data/raw'
OUT = ROOT / 'data/processed'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def save(frame, name):
    path = OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    frame.to_csv(path, index=False, lineterminator='\n')


def main():
    for item in json.loads((ROOT / 'data/download_manifest.json').read_text()):
        path = ROOT / 'data' / item['file']
        require(hashlib.sha256(path.read_bytes()).hexdigest() == item['sha256'], f'Source checksum changed: {path}')
    report = {'schema_version': 1, 'sources': {}}
    mta = pd.read_csv(RAW / 'mta_ridership/MTA_M15_Hourly_Ridership_2025_Q1.csv')
    before = len(mta)
    keys = ['transit_timestamp', 'bus_route', 'payment_method', 'fare_class_category']
    for col in keys:
        mta[col] = mta[col].astype('string').str.strip()
    require(not mta[keys].isna().any().any() and not mta[keys].eq('').any().any(), 'Missing MTA keys')
    mta['transit_timestamp'] = pd.to_datetime(mta['transit_timestamp'], errors='raise')
    for col in ['ridership', 'transfers']:
        mta[col] = pd.to_numeric(mta[col], errors='raise')
        require(mta[col].notna().all() and mta[col].ge(0).all(), f'Invalid {col}')
    mta = mta.drop_duplicates().sort_values(keys).reset_index(drop=True)
    require(not mta.duplicated(keys).any(), 'Conflicting MTA category records')
    require(mta['bus_route'].eq('M15').all(), 'Unexpected route')
    require(mta['transit_timestamp'].ge('2025-01-01').all() and mta['transit_timestamp'].lt('2025-04-01').all(), 'Unexpected MTA date')
    save(mta, 'mta_ridership/m15_category_hourly.csv')
    hourly = mta.groupby(['transit_timestamp', 'bus_route'], as_index=False)[['ridership', 'transfers']].sum()
    hourly = hourly.sort_values(['transit_timestamp', 'bus_route']).reset_index(drop=True)
    require(len(hourly) == 2160 and hourly['transit_timestamp'].nunique() == 2160, 'Expected 90 complete days of hourly M15 data')
    require(hourly['ridership'].sum() == mta['ridership'].sum(), 'Aggregation changed total ridership')
    hourly['hour'] = hourly['transit_timestamp'].dt.hour
    hourly['day_of_week'] = hourly['transit_timestamp'].dt.dayofweek
    hourly['is_weekend'] = hourly['day_of_week'].ge(5).astype(int)
    hourly['split'] = 'train'
    hourly.loc[hourly['transit_timestamp'].ge('2025-03-01'), 'split'] = 'validation'
    hourly.loc[hourly['transit_timestamp'].ge('2025-03-16'), 'split'] = 'test'
    save(hourly, 'mta_ridership/m15_route_hourly.csv')
    report['sources']['mta_ridership'] = {'raw_rows': before, 'clean_rows': len(mta), 'exact_duplicates_removed': before - len(mta), 'missing_values': int(mta.isna().sum().sum()), 'hourly_rows': len(hourly), 'total_ridership': float(hourly['ridership'].sum()), 'split_rows': hourly['split'].value_counts().to_dict()}

    weather = pd.read_csv(RAW / 'nairobi_weather/Nairobi_Hourly_Weather_2025.csv', skiprows=3)
    before = len(weather)
    weather.columns = ['time', 'temperature_c', 'relative_humidity_pct', 'precipitation_mm', 'rain_mm', 'cloud_cover_pct', 'wind_speed_kmh']
    weather['time'] = pd.to_datetime(weather['time'], errors='raise')
    for col in weather.columns[1:]:
        weather[col] = pd.to_numeric(weather[col], errors='raise')
    require(not weather.isna().any().any(), 'Missing weather values; review before filling')
    weather = weather.drop_duplicates().sort_values('time').reset_index(drop=True)
    require(not weather.duplicated('time').any(), 'Conflicting weather hours')
    require(len(weather) == 8760 and weather['time'].equals(pd.Series(pd.date_range('2025-01-01', periods=8760, freq='h'), name='time')), 'Incomplete weather calendar')
    for col in ['relative_humidity_pct', 'cloud_cover_pct']:
        require(weather[col].between(0, 100).all(), f'Out of range: {col}')
    for col in ['precipitation_mm', 'rain_mm', 'wind_speed_kmh']:
        require(weather[col].ge(0).all(), f'Negative weather value: {col}')
    save(weather, 'nairobi_weather/hourly_2025.csv')
    report['sources']['nairobi_weather'] = {'raw_rows': before, 'clean_rows': len(weather), 'exact_duplicates_removed': before - len(weather), 'missing_values': 0, 'timezone': 'Africa/Nairobi'}

    gtfs_report = {}
    for path in sorted((RAW / 'digital_matatus/GTFS_files').glob('*.txt')):
        frame = pd.read_csv(path, dtype=str, keep_default_na=False)
        before = len(frame)
        for col in frame:
            frame[col] = frame[col].str.strip()
        frame = frame.drop_duplicates().sort_values(list(frame.columns)).reset_index(drop=True)
        save(frame, 'digital_matatus/' + path.stem + '.csv')
        gtfs_report[path.stem] = {'raw_rows': before, 'clean_rows': len(frame), 'exact_duplicates_removed': before - len(frame), 'blank_cells': int(frame.eq('').sum().sum())}
    report['sources']['digital_matatus'] = gtfs_report
    report['limitations'] = ['MTA timestamps retain source local clock values (New York); no timezone conversion is applied.', 'Nairobi weather is separate from New York ridership and must not be joined to it.', 'GTFS cleanup preserves strings and optional blanks; it does not validate current routes or repair relationships.', 'No local field labels or model accuracy are supplied.']
    (ROOT / 'reports').mkdir(exist_ok=True)
    (ROOT / 'reports/data_quality.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
