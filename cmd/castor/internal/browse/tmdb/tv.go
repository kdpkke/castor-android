package tmdb

import (
	"context"
	"strconv"
)

type TVDetails struct {
	Name    string   `json:"name"`
	Seasons []Season `json:"seasons"`
}

type Season struct {
	SeasonNumber int    `json:"season_number"`
	Name         string `json:"name"`
	EpisodeCount int    `json:"episode_count"`
	AirDate      string `json:"air_date"`
}

// Unaired hides only positively future-dated; undated stays (episodes may have dates).
func (s Season) Unaired() bool { return s.AirDate > today() }

type Episode struct {
	EpisodeNumber int    `json:"episode_number"`
	Name          string `json:"name"`
	Overview      string `json:"overview"`
	AirDate       string `json:"air_date"`
	StillPath     string `json:"still_path"`
}

// Unaired reads date as Season.Unaired; handles partial-run seasons.
func (e Episode) Unaired() bool { return e.AirDate > today() }

type SeasonDetails struct {
	Episodes []Episode `json:"episodes"`
}

func (c *Client) TV(ctx context.Context, id int) (*TVDetails, error) {
	var d TVDetails
	if err := c.get(ctx, "/"+string(MediaTV)+"/"+strconv.Itoa(id), nil, &d); err != nil {
		return nil, err
	}
	return &d, nil
}

func (c *Client) Season(ctx context.Context, tvID, seasonNumber int) (*SeasonDetails, error) {
	var d SeasonDetails
	path := "/" + string(MediaTV) + "/" + strconv.Itoa(tvID) + "/season/" + strconv.Itoa(seasonNumber)
	if err := c.get(ctx, path, nil, &d); err != nil {
		return nil, err
	}
	return &d, nil
}
