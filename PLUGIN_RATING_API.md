# Plugin Rating & Installation Tracking API

## Overview

The plugin system now includes comprehensive rating and installation tracking features:

✅ **5-Star Rating System** - Users can rate plugins from 1-5 stars  
✅ **Optional Reviews** - Users can leave detailed reviews with merits/demerits  
✅ **Installation Tracking** - Track who installed what, when, and how many times  
✅ **Statistics** - Real-time stats on downloads, ratings, and active installs  
✅ **User-Specific Data** - Track individual user behavior and preferences

---

## Rating System

### Rate a Plugin

**POST** `/api/plugins/{pluginId}/rate`

Submit or update your rating for a plugin.

**Request Body:**
```json
{
  "stars": 5,
  "review": "Excellent plugin! Very useful for my workflow.",
  "merits": "Easy to use, great documentation, fast performance",
  "demerits": "Could use more customization options",
  "recommended": true
}
```

**Fields:**
- `stars` (required): Integer 1-5
- `review` (optional): General review text
- `merits` (optional): What you like about the plugin
- `demerits` (optional): What could be improved
- `recommended` (optional): Boolean, would you recommend this?

**Response:**
```json
{
  "id": "rating_123",
  "pluginId": "swrl-editor-plugin",
  "userId": "user_456",
  "username": "john.doe@example.com",
  "stars": 5,
  "review": "Excellent plugin!...",
  "merits": "Easy to use...",
  "demerits": "Could use more...",
  "recommended": true,
  "helpfulCount": 0,
  "createdAt": "2025-11-23T10:30:00",
  "updatedAt": "2025-11-23T10:30:00"
}
```

### Get All Ratings for a Plugin

**GET** `/api/plugins/{pluginId}/ratings`

Retrieve all ratings and reviews for a specific plugin.

**Response:**
```json
[
  {
    "id": "rating_123",
    "pluginId": "swrl-editor-plugin",
    "userId": "user_456",
    "username": "john.doe@example.com",
    "stars": 5,
    "review": "Excellent plugin!",
    "merits": "Easy to use",
    "demerits": null,
    "recommended": true,
    "helpfulCount": 12,
    "createdAt": "2025-11-23T10:30:00",
    "updatedAt": "2025-11-23T10:30:00"
  },
  {
    "id": "rating_124",
    "pluginId": "swrl-editor-plugin",
    "userId": "user_789",
    "username": "jane.smith@example.com",
    "stars": 4,
    "review": "Good plugin, needs improvement",
    "merits": "Good features",
    "demerits": "Slow on large files",
    "recommended": true,
    "helpfulCount": 5,
    "createdAt": "2025-11-22T15:20:00",
    "updatedAt": "2025-11-22T15:20:00"
  }
]
```

### Get My Rating for a Plugin

**GET** `/api/plugins/{pluginId}/my-rating`

Get the current user's rating for a plugin.

**Response:**
- 200 OK with rating data (if exists)
- 204 No Content (if user hasn't rated yet)

### Delete My Rating

**DELETE** `/api/plugins/{pluginId}/my-rating`

Remove your rating for a plugin.

**Response:** 204 No Content

### Mark Review as Helpful

**POST** `/api/plugins/ratings/{ratingId}/helpful`

Increment the "helpful" counter for a review.

**Response:** 200 OK

### Get Rating Statistics

**GET** `/api/plugins/{pluginId}/rating-stats`

Get aggregated rating statistics for a plugin.

**Response:**
```json
{
  "totalRatings": 127,
  "averageRating": 4.3,
  "distribution": {
    "1": 2,
    "2": 5,
    "3": 15,
    "4": 45,
    "5": 60
  },
  "recommendedCount": 98
}
```

---

## Installation Tracking

### Track Plugin Installation

**POST** `/api/plugins/{pluginId}/install?version=1.0.0`

Track when a user installs a plugin.

**Query Parameters:**
- `version` (optional): Plugin version installed (uses latest if not specified)

**Response:** 200 OK

**Behavior:**
- Creates or updates installation record
- Increments user's total install count for this plugin
- Updates plugin's total download count
- Records timestamp

### Track Plugin Uninstallation

**POST** `/api/plugins/{pluginId}/uninstall`

Track when a user uninstalls a plugin.

**Response:** 200 OK

**Behavior:**
- Marks installation as inactive (isActive = false)
- Records uninstallation timestamp
- Keeps history (doesn't delete record)

### Get Plugin Statistics

**GET** `/api/plugins/{pluginId}/stats`

Get comprehensive statistics for a plugin.

**Response:**
```json
{
  "pluginId": "swrl-editor-plugin",
  "totalInstalls": 1523,
  "activeInstalls": 1401,
  "totalDownloads": 1523,
  "averageRating": 4.3,
  "totalRatings": 127,
  "ratingDistribution": {
    "1": 2,
    "2": 5,
    "3": 15,
    "4": 45,
    "5": 60
  },
  "recommendedCount": 98,
  "totalReviews": 85
}
```

**Fields Explained:**
- `totalInstalls`: Total times plugin has been installed (includes reinstalls)
- `activeInstalls`: Number of users who currently have it installed
- `totalDownloads`: Same as totalInstalls (for compatibility)
- `averageRating`: Average star rating (0.0 - 5.0)
- `totalRatings`: Number of ratings received
- `ratingDistribution`: Count of each star rating
- `recommendedCount`: How many users recommended it
- `totalReviews`: Number of text reviews (not just stars)

### Get My Installation Info

**GET** `/api/plugins/{pluginId}/my-install`

Get your installation history for a plugin.

**Response:**
```json
{
  "id": "install_123",
  "pluginId": "swrl-editor-plugin",
  "userId": "user_456",
  "username": "john.doe@example.com",
  "version": "1.0.0",
  "isActive": true,
  "totalInstalls": 3,
  "firstInstalledAt": "2025-10-15T09:20:00",
  "lastInstalledAt": "2025-11-20T14:30:00",
  "lastUninstalledAt": "2025-11-19T16:45:00",
  "createdAt": "2025-10-15T09:20:00",
  "updatedAt": "2025-11-20T14:30:00"
}
```

**Fields Explained:**
- `totalInstalls`: How many times YOU installed this plugin
- `isActive`: Whether you currently have it installed
- `firstInstalledAt`: When you first installed it
- `lastInstalledAt`: Most recent installation
- `lastUninstalledAt`: When you last uninstalled (if applicable)

### Get All My Installed Plugins

**GET** `/api/plugins/my-installs`

Get list of all plugins you've installed.

**Response:**
```json
[
  {
    "id": "install_123",
    "pluginId": "swrl-editor-plugin",
    "userId": "user_456",
    "version": "1.0.0",
    "isActive": true,
    "totalInstalls": 3,
    "firstInstalledAt": "2025-10-15T09:20:00",
    "lastInstalledAt": "2025-11-20T14:30:00"
  },
  {
    "id": "install_124",
    "pluginId": "graph-view-plugin",
    "userId": "user_456",
    "version": "1.0.0",
    "isActive": false,
    "totalInstalls": 1,
    "firstInstalledAt": "2025-11-01T11:00:00",
    "lastInstalledAt": "2025-11-01T11:00:00",
    "lastUninstalledAt": "2025-11-10T13:30:00"
  }
]
```

### Check if Plugin is Installed

**GET** `/api/plugins/{pluginId}/is-installed`

Check if you currently have a plugin installed.

**Response:**
```json
{
  "isInstalled": true
}
```

### Get My Install Count

**GET** `/api/plugins/{pluginId}/my-install-count`

Get how many times you've installed a specific plugin.

**Response:**
```json
{
  "installCount": 3
}
```

---

## Frontend Integration

### Using pluginLoader Service

```typescript
import { pluginLoader } from './services/pluginLoader';

// Install plugin (automatically tracks)
await pluginLoader.installPlugin('swrl-editor-plugin');

// Uninstall plugin (automatically tracks)
await pluginLoader.uninstallPlugin('swrl-editor-plugin');

// Rate a plugin
await pluginLoader.ratePlugin(
  'swrl-editor-plugin',
  5, // stars
  'Excellent plugin!', // review (optional)
  'Easy to use, great documentation', // merits (optional)
  'Could use more features', // demerits (optional)
  true // recommended (optional)
);

// Get your rating
const myRating = await pluginLoader.getUserRating('swrl-editor-plugin');

// Get all ratings
const allRatings = await pluginLoader.getPluginRatings('swrl-editor-plugin');

// Get stats
const stats = await pluginLoader.getPluginStats('swrl-editor-plugin');
console.log(`Active installs: ${stats.activeInstalls}`);
console.log(`Average rating: ${stats.averageRating}`);
```

### Using RatingModal Component

```tsx
import { RatingModal } from './components/RatingModal';

const [showRatingModal, setShowRatingModal] = useState(false);
const [currentRating, setCurrentRating] = useState(null);

// Load current rating
useEffect(() => {
  pluginLoader.getUserRating('swrl-editor-plugin')
    .then(setCurrentRating);
}, []);

// In your JSX
{showRatingModal && (
  <RatingModal
    pluginId="swrl-editor-plugin"
    pluginName="SWRL Editor"
    currentRating={currentRating}
    onClose={() => setShowRatingModal(false)}
    onSubmit={async (rating) => {
      await pluginLoader.ratePlugin(
        'swrl-editor-plugin',
        rating.stars,
        rating.review,
        rating.merits,
        rating.demerits,
        rating.recommended
      );
    }}
  />
)}
```

---

## Database Models

### PluginRating Collection

```javascript
{
  _id: ObjectId,
  pluginId: String (indexed),
  userId: String (indexed),
  username: String,
  stars: Number (1-5),
  review: String (optional),
  merits: String (optional),
  demerits: String (optional),
  recommended: Boolean,
  helpfulCount: Number,
  createdAt: Date,
  updatedAt: Date
}
```

### PluginUserInstall Collection

```javascript
{
  _id: ObjectId,
  pluginId: String (indexed),
  userId: String (indexed),
  username: String,
  version: String,
  isActive: Boolean,
  totalInstalls: Number,
  firstInstalledAt: Date,
  lastInstalledAt: Date,
  lastUninstalledAt: Date,
  createdAt: Date,
  updatedAt: Date
}
```

---

## Use Cases

### Plugin Marketplace Display

Show plugin with rating badge:
```tsx
<div className="plugin-card">
  <h3>{plugin.name}</h3>
  <div className="rating">
    {[1,2,3,4,5].map(star => (
      <Star 
        key={star}
        className={star <= plugin.averageRating ? 'filled' : 'empty'}
      />
    ))}
    <span>({plugin.totalRatings} reviews)</span>
  </div>
  <div className="installs">
    {plugin.activeInstalls} active installs
  </div>
</div>
```

### User Profile - My Plugins

```tsx
const myInstalls = await fetch('/api/plugins/my-installs').then(r => r.json());

myInstalls.forEach(install => {
  console.log(`${install.pluginId}: installed ${install.totalInstalls} times`);
  if (install.isActive) {
    console.log('✓ Currently installed');
  }
});
```

### Analytics Dashboard

```tsx
const stats = await pluginLoader.getPluginStats('swrl-editor-plugin');

// Show distribution chart
stats.ratingDistribution.forEach((count, stars) => {
  const percentage = (count / stats.totalRatings) * 100;
  renderBar(stars, percentage);
});

// Show recommendation rate
const recommendRate = (stats.recommendedCount / stats.totalRatings) * 100;
console.log(`${recommendRate}% of users recommend this plugin`);
```

---

## Summary

✅ **Complete Rating System**
- 1-5 star ratings
- Optional text reviews
- Separate merits/demerits fields
- Recommendation tracking
- Helpful vote system

✅ **Installation Tracking**
- Per-user install counts
- Active vs historical installs
- Installation/uninstallation timestamps
- Version tracking

✅ **Statistics & Analytics**
- Real-time aggregate stats
- Rating distribution
- Active install count
- Recommendation rates

✅ **Privacy & User Control**
- Users control their ratings
- Can update or delete ratings
- Per-user installation history
- No sensitive data exposed

The system is production-ready and fully integrated with the plugin marketplace!
